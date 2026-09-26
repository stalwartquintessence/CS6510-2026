# Week 2 — Layered Architecture

The same self-checkout contract as week 1 (`spec/self-checkout-openapi.yaml`), driven by the same
unmodified `load-client/`, restructured into four explicit layers with the boundaries **enforced by
tests** rather than by convention.

Week 1 was already `controller → service → repository`, so this is not a rewrite. The work was
removing the places where that layering leaked, and making it impossible to re-introduce them.

## The layers

```
           ┌──────────────────────────────────────────────┐
  api/     │ controllers · DTOs · mappers · error handler  │  HTTP lives here and nowhere else
           └───────────────┬──────────────────┬───────────┘
                           ▼                  ▼
  transaction/  ┌────────────────────┐  ┌──────────────┐
  analytics/    │ checkout lifecycle │─▶│  analytics   │  business rules, transaction boundaries
                │ catalog · inventory│  │ hopping window│
                └─────────┬──────────┘  └──────┬───────┘
                          ▼                    ▼
  persistence/   ┌─────────────────────────────────────┐
                 │ ItemDao · TransactionDao ·           │  JPA lives here and nowhere else
                 │ PopularItemsDao  →  entities · JPA   │
                 └──────────────────┬──────────────────┘
                                    ▼
  domain/        ┌─────────────────────────────────────┐
                 │ Basket · ItemSnapshot · errors       │  shared kernel, depends on nothing
                 └─────────────────────────────────────┘
```

| Layer | Package | Owns |
| ----- | ------- | ---- |
| **API** | `com.supermarket.api` | The 7 HTTP endpoints, wire DTOs, domain→DTO mappers, and the single place that maps failures to status codes |
| **Transactions** | `com.supermarket.transaction` | Checkout lifecycle (start/scan/complete), plus the `catalog` and `inventory` modules |
| **Analytics** | `com.supermarket.analytics` | The hopping window, its recompute policy, and the persisted ranking |
| **Database access** | `com.supermarket.persistence` | DAO ports, JPA entities, Spring Data interfaces |
| *(shared kernel)* | `com.supermarket.domain` | `Basket` aggregate, `ItemSnapshot`, domain errors — depends on no other layer |

`catalog` and `inventory` sit **inside** the transactions layer: both exist to serve checkout, and
stock is only ever mutated by a completing transaction. Promoting inventory to its own peer layer
would be a package move plus one extra rule.

## What the layering actually buys

Each of these was a real coupling in week 1:

| Week 1 | Week 2 |
| ------ | ------ |
| `TransactionService` injected `InventoryService` **and** `InventoryRepository`, reading stock directly in `scan()` | Goes through `InventoryService` for both reads and writes |
| `AnalyticsService` injected `InventoryRepository` to resolve SKU→name | Uses `ItemDao`, a declared port, not another module's repository |
| `InventoryRepository` had four different callers | `ItemDao` is the single owner of the `items` table; the Spring Data interfaces are package-private |
| `DataInitializer` (in `config/`) wrote entities straight to the repository | `CatalogSeeder` only triggers; `CatalogService` owns the work |
| Services returned API DTO records — an upward dependency on the web layer | Services return domain views; `api/mapper/` converts |
| `recompute()` called `this.persistRanking()`, so its `@Transactional` was a **silent no-op** | `PopularItemsRankingWriter` is a separate bean, so the proxy is really in the call path |
| `InventoryService.decrement` had no `@Transactional` and just assumed a caller had opened one | `Propagation.MANDATORY` — it now fails loudly instead of silently dropping the row lock |
| Window state was fields on the analytics service | `ScanWindow` is its own bean |
| `BadRequestException` / `NotFoundException` — HTTP vocabulary below the web layer | `InvalidRequestException` / `ItemNotFoundException`; status mapping lives only in `GlobalExceptionHandler` |
| `spring-boot-starter-validation` declared but never used | `@NotBlank` + `@Valid`, mapped to the contract's `INVALID_REQUEST` |

The wire contract is byte-for-byte unchanged: same paths, same field names, same status codes,
same `error` codes (`INVALID_REQUEST`, `UNKNOWN_SKU`, `TRANSACTION_NOT_FOUND`,
`TRANSACTION_NOT_OPEN`, `EMPTY_BASKET`).

## The boundaries are tested, not just documented

`src/test/java/com/supermarket/architecture/LayeredArchitectureTest.java` holds 7 ArchUnit rules
that run on every `./mvnw test`:

1. The layer graph itself (who may call whom)
2. JPA entities may not escape the database access layer
3. `org.springframework.data..` / `jakarta.persistence..` confined to the database access layer
4. `org.springframework.web..` / `org.springframework.http..` confined to the API layer
5. The domain kernel is a leaf
6. No package cycles
7. Only `persistence.jpa` may touch a Spring Data repository

These were verified to actually fail: temporarily giving `CatalogController` an `ItemDao` field
trips rule 1 (*"where layer 'Persistence' may only be accessed by layers ['Transactions',
'Analytics']"*), and giving it an entity field trips rules 1 and 2.

## Running it

Requires JDK 17+ and Docker.

```bash
cd week2-layered
docker compose up -d                       # PostgreSQL (supermarket-pg-week2, host port 5433)
./mvnw test                                # the ArchUnit rules
./mvnw -DskipTests package
java -jar target/week2-layered-1.0.0.jar
```

Postgres uses host port **5433** and its own container/volume names, so week 1's stack can stay
running alongside it.

The catalog seeder is a `CommandLineRunner`, so it finishes shortly *after* the port opens — wait
for `Seeded catalog: 2000 items` in the log before driving load, or the client will see an empty
catalog.

```bash
# in another shell
cd ../load-client && ./build.sh
./run.sh --baseUrl=http://localhost:8080 --stations=10  --duration=60  --reportDir=../week2-layered/reports
./run.sh --baseUrl=http://localhost:8080 --stations=200 --duration=180 --reportDir=../week2-layered/reports
```

`docker compose down -v` resets stock for a fresh run (the seeder re-runs on an empty database).

## Results (this machine)

Both reports are in `reports/`: `report-20260925-231818.json` (default) and
`report-20260925-232154.json` (stress). Week 1's figures are from its own committed reports.

### Default mode — 10 stations, 60 s

| Metric | Week 1 | Week 2 |
| ------ | ------ | ------ |
| Throughput | 98.4 tx/sec | **100.3 tx/sec** |
| Items/sec | 1 039.6 | **1 043.7** |
| START p95 / p99 | 4.8 / 8.2 ms | **4.5 / 6.3 ms** |
| SCAN p95 / p99 | 12.0 / 19.0 ms | **10.5 / 14.5 ms** |
| COMPLETE p95 / p99 | 71.3 / 111.1 ms | **68.1 / 116.9 ms** |
| Errors | 0 | **0** |

### Stress mode — 200 stations, 180 s

| Metric | Week 1 | Week 2 |
| ------ | ------ | ------ |
| Throughput | 74.4 tx/sec | **71.3 tx/sec** |
| Items/sec | 781.6 | **748.4** |
| START p95 / p99 | 235 / 397 ms | **275 / 442 ms** |
| SCAN p95 / p99 | 245 / 398 ms | **273 / 424 ms** |
| COMPLETE p95 / p99 | 1.59 / 2.59 s | **1.75 / 2.77 s** |
| Errors | 0 | **0** |

**The honest reading: nothing moved.** Default mode is a few percent faster, stress mode a few
percent slower, and both differences are inside the run-to-run variance of a workload whose tail is
dominated by row-lock contention on Zipf-hot SKUs. That is the expected and correct result — this
week changed *where the code lives*, not what it does on the hot path. The pessimistic row lock,
the sorted-SKU lock order, HikariCP 50, Tomcat 200 and the 1000/500 window were all held identical
precisely so this comparison would mean something. Anyone reporting a speedup from "adding layers"
should be asked what else they changed.

The popular-items ranking is also stable across both implementations — `SKU-000001`, `SKU-000002`,
`SKU-000003` in that order — which is what should happen, since analytics is a feature, not an
architectural property.

### Correctness invariant

Checked against the database after each run:

- **Default:** 0 lost updates, 0 negative-stock rows. 62 741 units decremented = 62 741 units sold
  = the client's reported scan count, exactly.
- **Stress:** 0 lost updates, 0 negative-stock rows. `SKU-000001` saw demand of 16 700 units
  against 10 000 supply and correctly floored at 0 — legitimate demand-over-supply (the Zipf
  sampler sends ~12 % of scans to rank 1), not a concurrency defect.

```bash
docker exec -i supermarket-pg-week2 psql -U supermarket -d supermarket <<'SQL'
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

Both return 0.

See [ARCHITECTURE.md](ARCHITECTURE.md) for characteristics, trade-offs and the evidence behind them.
