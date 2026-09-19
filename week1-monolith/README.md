# Week 1 — Layered Monolith

A single Spring Boot 3 process implementing the shared self-checkout API
contract (`../spec/self-checkout-openapi.yaml`). Everything — catalog,
transactions, inventory, analytics — lives in one deployable, one JVM, one
PostgreSQL database, wired as classic **controller → service → repository**
layers.

## Architecture

```
com.supermarket
├── controller   CatalogController, TransactionController,
│                InventoryController, AnalyticsController, GlobalExceptionHandler
├── service      CatalogService, TransactionService, InventoryService, AnalyticsService
├── repository   TransactionRepository, InventoryRepository, PopularItemRepository
├── model        InventoryItem, Transaction, TransactionItem, PopularItem, TransactionStatus
├── dto          Dtos (all request/response records)
└── config       DatabaseConfig, DataInitializer (seeds 2000 items × 10 000 units)
```

Tables (`ddl-auto=update` creates them from the entities):
`items`, `transactions`, `transaction_items`, `popular_items`.

### How the correctness invariant is held

Stock is decremented **only at completion**, never at scan time. On
`POST /transactions/{id}/complete`, `TransactionService`:

1. sorts the basket lines by SKU (a stable lock-acquisition order, so two
   concurrent completions touching the same pair of SKUs can never deadlock),
   then
2. for each SKU calls `InventoryService.decrement`, which loads the row with a
   **`PESSIMISTIC_WRITE`** lock (`SELECT … FOR UPDATE`) inside the completion's
   transaction and decrements it, clamped at zero.

Because every decrement of a given SKU serializes on that row lock, the classic
"two stations both buy the last unit" lost update is impossible: `initial_stock
− final_stock` equals the number of units actually sold, and stock never goes
negative.

### Analytics (hopping window)

`AnalyticsService` keeps the last `windowSize` (1000) scan SKUs in a
`ConcurrentLinkedQueue` with an `AtomicLong` global scan sequence. Every
`slideInterval` (500) scans it recomputes the top-N ranking under a
`ReentrantLock` and rewrites the `popular_items` table, recording the global
sequence numbers that bound the window (`windowStart`/`windowEnd`).

## Running it

Prerequisites: JDK 17+ and Docker.

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Build and run (uses the bundled Maven wrapper)
./mvnw -DskipTests package
java -jar target/week1-monolith-1.0.0.jar
# (or: ./mvnw spring-boot:run)

# 3. In another shell, drive it with the shared load client
cd ../load-client && ./build.sh
./run.sh --baseUrl=http://localhost:8080 --stations=10 --duration=60 \
         --reportDir=../week1-monolith/reports
```

To reset stock for a fresh run: `docker compose down -v` (the seeder re-runs on
an empty database).

## Results (this machine)

Reports are in `reports/`.

| Run                | Tx/sec | COMPLETE p95 | COMPLETE p99 | COMPLETE max | Errors |
| ------------------ | ------ | ------------ | ------------ | ------------ | ------ |
| Baseline (10 stn)  | ~98    | ~71 ms       | ~111 ms      | ~389 ms      | 0      |
| Stress (200 stn)   | ~74    | ~1.6 s       | ~2.6 s       | ~6.7 s       | 0      |

At 10 stations everything is fast. At 200 stations throughput stays flat while
`COMPLETE_TRANSACTION` tail latency blows up an order of magnitude — this is the
pessimistic row-lock contention on the Zipf-hot SKUs, and it is exactly the
signature this architecture is expected to show. Scans and starts stay far
cheaper because they take no inventory lock.

### Correctness invariant

Checked directly against the database after each run:

- **Baseline (supply ≥ demand):** 0 rows with negative stock, **0 invariant
  violations** — `Σ(10000 − stock)` = total completed units sold = 62 504,
  matching the client's item count exactly.
- **Stress (200 stations):** 0 negative-stock rows, and **0 mismatches on any
  still-in-stock SKU** — i.e. no lost updates anywhere. The single hottest SKU
  (`SKU-000001`) received demand (17 516 units) exceeding its 10 000 supply; its
  stock correctly floored at 0 rather than going negative. That is legitimate
  demand-over-supply, not a concurrency defect — the Zipf sampler sends ~12 % of
  all scans to rank 1, so any run long enough will exhaust the top item.

Invariant query (run inside the container):

```bash
docker exec -i supermarket-pg psql -U supermarket -d supermarket <<'SQL'
WITH sold AS (
  SELECT ti.sku, SUM(ti.quantity) AS qty_sold
  FROM transaction_items ti JOIN transactions t ON t.id = ti.tx_id
  WHERE t.status = 'COMPLETED' GROUP BY ti.sku)
SELECT count(*) AS lost_update_bugs
FROM items i LEFT JOIN sold s ON s.sku = i.sku
WHERE i.stock > 0 AND (10000 - i.stock) <> COALESCE(s.qty_sold, 0);
SELECT count(*) AS negative_stock_rows FROM items WHERE stock < 0;
SQL
```

Both should return 0.
