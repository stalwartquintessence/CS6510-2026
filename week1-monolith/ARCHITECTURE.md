# Self-Checkout Monolithic Architecture Analysis

> All latency figures below are taken directly from the two committed reports in
> `reports/` (baseline `report-20260918-213707.json`, stress
> `report-20260918-214130.json`) and the post-run SQL invariant checks. The
> hardware is a single developer laptop; PostgreSQL runs in Docker on the same
> machine.

## Architectural Characteristics

| Characteristic      | Concrete requirement                                                                 |
| ------------------- | ------------------------------------------------------------------------------------ |
| **Consistency**     | Stock never negative; no double-sells; `initial − final == units sold` per in-stock SKU |
| **Performance**     | Endpoint latency under 1 s p95 at the assignment's default load (10 stations)         |
| **Scalability**     | Serve 100+ concurrent checkout stations without errors                                |
| **Reliability**     | Committed data survives process/container restarts (PostgreSQL durability)            |
| **Maintainability** | Strict layered separation (controller → service → repository)                         |
| **Observability**   | Every transaction and basket line persisted as an audit trail (`transactions`, `transaction_items`) |
| **Portability**     | One `docker compose up` + one runnable jar; no host-installed Postgres/Maven required |

## Top 3 Prioritized Characteristics

### 1. Consistency (Inventory Accuracy)

- **Why:** No double-sells is the business-critical invariant and the explicit
  point of the exercise — a naive check-then-decrement fails it under load.
- **How:** Stock is decremented **only at completion**, under a
  `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`SELECT … FOR UPDATE`) read of the
  `InventoryItem` row, inside the completion's `@Transactional` boundary. Locks
  for a multi-SKU basket are acquired in **sorted SKU order** so concurrent
  completions can never deadlock.
- **Trade-off:** Serializing on the row lock is exactly what caps throughput and
  inflates tail latency once many stations contend for the same hot SKUs.
- **Evidence:** After the 200-station / 180 s stress run — **0 negative-stock
  rows** and **0 mismatches on any still-in-stock SKU** (no lost updates). The
  cost of that guarantee is visible: `COMPLETE_TRANSACTION` p99 rose from
  **111 ms** (10 stations) to **2.59 s** (200 stations).

### 2. Performance (Sub-1s Response Times)

- **Why:** Checkout latency is customer-facing; slow completes mean queues.
- **How:** HikariCP connection pool sized at **50** (`application.properties`),
  Tomcat worker pool at **200**, batched JPA inserts, and `open-in-view=false`
  so no DB connection is held during view rendering. The catalog is **seeded
  into PostgreSQL at startup** and served from the database (there is no
  in-memory catalog cache in this week's implementation).
- **Trade-off:** No read caching means `/items` and per-scan SKU lookups always
  hit the database — simple and always-fresh, at the cost of DB round-trips.
- **Evidence:** At the assignment default (10 stations), all endpoints are far
  under the 1 s p95 SLA — START p95 **4.8 ms**, SCAN p95 **12.0 ms**, COMPLETE
  p95 **71.3 ms**. Under 200-station stress the SLA holds for START (p95
  **235 ms**) and SCAN (p95 **245 ms**) but is **exceeded for COMPLETE** (p95
  **1.59 s**) — the lock-contention path, as designed.

### 3. Scalability (100+ Concurrent Stations)

- **Why:** Peak load (holiday rush) must be handled without failures.
- **How:** 200 Tomcat threads front a 50-connection pool; the analytics sliding
  window is kept in-process so scans don't fan out to another system.
- **Trade-off:** A monolith scales **up**, not **out** — one JVM and one
  database are the ceiling. True horizontal scale-out (and isolating the
  inventory hot path) is what later service-based / microservice weeks explore.
- **Evidence:** The 200-station run completed with **0 errors** across 13 566
  transactions and 142 537 scans. Throughput dropped from **98.4 → 74.4 tx/sec**
  (~24%) as contention rose — degradation without failure, which is the expected
  and acceptable behavior for a monolith at this scale.

## Load Test Results

### Baseline Run (10 stations, 60 seconds)

- Throughput: **98.4 tx/sec** (1 039.6 items/sec), 5 918 transactions
- p95 / p99 latency:
  - START_TRANSACTION: **4.8 ms** / **8.2 ms**
  - SCAN_ITEM: **12.0 ms** / **19.0 ms**
  - COMPLETE_TRANSACTION: **71.3 ms** / **111.1 ms**
- Errors: **0**
- Correctness: every SKU satisfies `initial − final == units sold`
  (62 504 sold = 62 504 decremented); 0 negative-stock rows. Nothing depleted.

### Stress Run (200 stations, 180 seconds)

- Throughput: **74.4 tx/sec** (781.6 items/sec), 13 566 transactions
  (~24% drop vs baseline, under lock contention)
- p95 / p99 latency:
  - START_TRANSACTION: **235 ms** / **397 ms**
  - SCAN_ITEM: **245 ms** / **398 ms**
  - COMPLETE_TRANSACTION: **1.59 s** / **2.59 s** (max 6.74 s)
- Errors: **0**
- Correctness: **0 negative-stock rows; 0 lost updates** (all in-stock SKUs
  match the invariant exactly).
- Note: the highest-demand item (`SKU-000001`, Zipf rank 1, ~12% of all scans)
  received demand of **17 516 units** against its **10 000** supply. Its stock
  correctly **clamped at 0** rather than going negative. This is legitimate
  demand-over-supply — the spec gives completion no failure path — not a
  concurrency defect.

## Design Decisions

### Concurrency & Correctness

- Inventory decrements use pessimistic locking: `InventoryRepository`
  `@Lock(PESSIMISTIC_WRITE)` `findBySkuForUpdate` reads the row `FOR UPDATE`.
- The decrement runs inside `TransactionService.complete`'s `@Transactional`
  method (default propagation; PostgreSQL's default **READ_COMMITTED**
  isolation — no explicit isolation override was needed, since the row lock,
  not the isolation level, provides the mutual exclusion).
- Multi-SKU baskets lock rows in sorted SKU order to prevent deadlocks.
- Stock is clamped at 0 on decrement, so it can never go negative.
- Validated by the stress run: 0 negative-stock rows and 0 lost updates.

### Sliding Window Analytics

- `AnalyticsService` keeps the last 1 000 scan SKUs in a `ConcurrentLinkedQueue`
  with an `AtomicLong` global scan sequence.
- Every 500 scans (a hopping window) it recomputes the top-N ranking and
  rewrites the `popular_items` table, recording `windowStart`/`windowEnd`
  (global scan sequence numbers) for client verification.
- The recompute is **synchronous** on the scan that crosses the slide boundary,
  but guarded by a `ReentrantLock.tryLock()` — concurrent scans that arrive
  mid-recompute skip it rather than block, so it never serializes the scan path.
  (Moving it fully off-thread is a natural future optimization.)

### Database Schema

- Four tables: `items`, `transactions`, `transaction_items`, `popular_items`
  (created from the JPA entities via `ddl-auto=update`).
- Primary keys are indexed automatically: `items.sku`, `popular_items.sku`,
  and the surrogate keys of `transactions`/`transaction_items`.
- `transaction_items.tx_id` is a foreign-key column (no explicit secondary
  index added this week — worth adding if transaction history queries grow).
- A named Docker volume (`supermarket-pgdata`) persists data across container
  restarts.

### Monolithic Limitations (to be addressed in later weeks)

- **Single JVM = single point of failure and a single scaling unit.** Every
  concern (catalog, transactions, inventory, analytics) shares one process and
  one connection pool, so the inventory lock hot path drags down unrelated
  endpoints' tail latency under stress.
- **Scale-up only.** There is no way to scale the contended inventory path
  independently of cheap read endpoints.
- Later architecture weeks (service-based, orchestration-driven, microservices,
  event-driven) address these by isolating the inventory service, scaling
  components independently, and decoupling analytics from the transaction path —
  measured against this same load client so the differences are attributable to
  architecture, not tooling.
