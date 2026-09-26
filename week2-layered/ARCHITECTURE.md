# Self-Checkout Layered Architecture Analysis

> All latency figures below come from the two committed reports in `reports/`
> (default `report-20260925-231818.json`, stress `report-20260925-232154.json`)
> and the post-run SQL invariant checks. Week 1's figures come from its own
> committed reports. Hardware is a single developer laptop with PostgreSQL in
> Docker on the same machine.

## What changed from week 1

Week 1 was a layered monolith by package naming (`controller → service → repository`). Week 2 is a
layered monolith by *construction*: each layer exposes interfaces, the layer below cannot see the
layer above, and seven ArchUnit rules fail the build when that stops being true.

The architectural substance of the change is **dependency direction**, not new functionality. Five
of week 1's couplings pointed the wrong way or sideways:

- the transactions service reached past the inventory module into its repository,
- the analytics service did the same,
- the seeding component skipped the service layer entirely,
- services returned web-layer DTO records (an upward dependency),
- and domain exceptions were named after HTTP status codes.

Two of week 1's couplings were also latent **defects**, not just untidiness:

- `AnalyticsService.recompute()` called `this.persistRanking()`, a self-invocation that bypasses
  the Spring proxy, so its `@Transactional(REQUIRED)` never applied. It worked only because the
  caller happened to already be in a transaction.
- `InventoryService.decrement` carried no transaction annotation at all and relied on an
  undocumented "my caller has opened one" contract. Had that ever been violated, the
  `PESSIMISTIC_WRITE` lock would have been released immediately and the oversell bug would have
  returned silently.

Both are now structurally impossible: the ranking writer is its own bean, and the decrement is
`Propagation.MANDATORY`.

## Architectural Characteristics

| Characteristic | Concrete requirement |
| -------------- | -------------------- |
| **Modularity** | Each layer reachable only through its interface; violations fail the build |
| **Consistency** | Stock never negative; no double-sells; `initial − final == units sold` per in-stock SKU |
| **Testability** | Layer boundaries are executable assertions, not documentation |
| **Performance** | Endpoint latency under 1 s p95 at the assignment's default load (10 stations) |
| **Scalability** | Serve 100+ concurrent checkout stations without errors |
| **Maintainability** | A change to persistence technology touches one layer; entities cannot leak upward |
| **Portability** | One `docker compose up` + one runnable jar |

## Top 3 Prioritized Characteristics

### 1. Modularity (Enforced Layer Boundaries)

- **Why:** This is the week's assignment, and it is the characteristic week 1 *claimed*
  ("strict layered separation") without any mechanism to back the claim up. An architecture that
  is only a naming convention decays at the first deadline.
- **How:** Four layers over a shared domain kernel. Each exposes interfaces (`TransactionService`,
  `InventoryService`, `CatalogService`, `AnalyticsService`, `ItemDao`, `TransactionDao`,
  `PopularItemsDao`); implementations are package-private. Spring Data repositories are
  package-private inside `persistence.jpa`, so the `items` table has exactly one owner. Seven
  ArchUnit rules run in `./mvnw test`.
- **Trade-off:** More types and an explicit mapping step. The `Basket` aggregate exists purely so
  the transactions layer can hold checkout rules without touching a JPA entity, and four mapper
  classes exist purely to keep wire shapes out of the service layer. At this size that is real
  overhead for a system one person can hold in their head; it pays off when the persistence
  technology or the wire contract changes independently — which is exactly what later weeks do.
- **Evidence:** 7 rules, 7 passing. Verified non-vacuous: giving `CatalogController` an `ItemDao`
  field fails with *"where layer 'Persistence' may only be accessed by layers ['Transactions',
  'Analytics']"*; giving it a JPA entity field fails two rules.

### 2. Consistency (Inventory Accuracy)

- **Why:** No double-sells remains the business-critical invariant, and restructuring is exactly
  the kind of change that quietly breaks it.
- **How:** Unchanged from week 1 on purpose. Stock is decremented **only at completion**, under a
  `@Lock(LockModeType.PESSIMISTIC_WRITE)` (`SELECT … FOR UPDATE`) read inside the completion's
  `@Transactional` boundary, with locks acquired in **sorted SKU order** so concurrent completions
  cannot deadlock. The lock primitive moved from `InventoryRepository` to `JpaItemDao`; the SQL is
  identical. `JpaTransactionDao.save` deliberately re-reads through the persistence context (a
  first-level cache hit, no extra SQL) rather than merging a detached copy, so writes still happen
  by Hibernate dirty checking at commit.
- **Trade-off:** Serializing on the row lock is still what caps throughput and inflates tail
  latency under contention. Layering neither helps nor hurts this.
- **Evidence:** After the 200-station / 180 s run — **0 lost updates, 0 negative-stock rows**.
  After the 10-station run — 62 741 units decremented = 62 741 units sold = the client's reported
  scan count, exactly.

### 3. Performance (Unchanged, Deliberately)

- **Why:** The grading question is how p95/p99 move against the previous week on the same
  hardware. That question only has a meaningful answer if exactly one thing changed.
- **How:** Every performance-relevant setting was held constant: HikariCP 50, Tomcat 200,
  `open-in-view=false`, batch size 50, window 1000 / slide 500, catalog 2000 × 10 000. The
  analytics recompute was deliberately **left synchronous** on the scan that crosses the slide
  boundary, even though moving it off the hot path is an obvious improvement — doing both at once
  would have made the comparison uninterpretable.
- **Trade-off:** This week banks no performance win. The known improvement (async recompute, so
  the 50-row `popular_items` rewrite stops committing inside a customer's checkout) is left on the
  table as a deliberate, documented choice.
- **Evidence:** Default mode 100.3 vs 98.4 tx/sec and COMPLETE p95 68.1 vs 71.3 ms; stress mode
  71.3 vs 74.4 tx/sec and COMPLETE p95 1.75 vs 1.59 s. Differences run in both directions and sit
  inside the run-to-run variance of a lock-contention-dominated workload. **The correct conclusion
  is that layering is free at runtime**, not that it made anything faster or slower.

## Load Test Results

### Default Run (10 stations, 60 seconds)

- Throughput: **100.3 tx/sec** (1 043.7 items/sec), 6 032 transactions, 62 741 scans
- p95 / p99 latency:
  - START_TRANSACTION: **4.5 ms** / **6.3 ms**
  - SCAN_ITEM: **10.5 ms** / **14.5 ms**
  - COMPLETE_TRANSACTION: **68.1 ms** / **116.9 ms** (max 291 ms)
- Errors: **0**
- Correctness: 62 741 sold = 62 741 decremented; 0 negative-stock rows; nothing depleted.

### Stress Run (200 stations, 180 seconds)

- Throughput: **71.3 tx/sec** (748.4 items/sec), 13 031 transactions, 136 719 scans
- p95 / p99 latency:
  - START_TRANSACTION: **275 ms** / **442 ms**
  - SCAN_ITEM: **273 ms** / **424 ms**
  - COMPLETE_TRANSACTION: **1.75 s** / **2.77 s** (max 5.64 s)
- Errors: **0**
- Correctness: **0 lost updates, 0 negative-stock rows**.
- `SKU-000001` (Zipf rank 1, ~12 % of all scans) saw demand of **16 700 units** against **10 000**
  supply and clamped at 0. The spec gives completion no failure path, so this is legitimate
  demand-over-supply, not a concurrency defect.

### Week-over-week

| | Week 1 default | Week 2 default | Week 1 stress | Week 2 stress |
| --- | --- | --- | --- | --- |
| Throughput (tx/sec) | 98.4 | 100.3 | 74.4 | 71.3 |
| START p95 | 4.8 ms | 4.5 ms | 235 ms | 275 ms |
| SCAN p95 | 12.0 ms | 10.5 ms | 245 ms | 273 ms |
| COMPLETE p95 | 71.3 ms | 68.1 ms | 1.59 s | 1.75 s |
| COMPLETE p99 | 111 ms | 117 ms | 2.59 s | 2.77 s |
| Errors | 0 | 0 | 0 | 0 |

The 1 s p95 SLA holds comfortably for every operation at the default load, and — as in week 1 —
holds for START and SCAN but is exceeded for COMPLETE under 200-station stress. Same architecture
shape, same bottleneck, same verdict.

Popular-items ranking is stable across both weeks (`SKU-000001`, `SKU-000002`, `SKU-000003`), which
is the expected result: analytics is a feature of the contract, not a property of the architecture.

**Caveat on the comparison.** Week 1's numbers were measured in a separate session, so background
machine state is not perfectly controlled. The default-mode figures differ by ~2 % in week 2's
favour and the stress-mode figures by ~4 % against it; treating either as a real effect would be
over-reading the data.

## Design Decisions

### Layer boundaries

- Interfaces at every boundary; implementations package-private. Callers depend on
  `InventoryService`, never `DefaultInventoryService`.
- The API layer is the only place `org.springframework.web` appears. The database access layer is
  the only place `org.springframework.data` and `jakarta.persistence` appear. `@Transactional` is
  deliberately allowed above the persistence layer — the unit of work belongs to the service that
  owns the business operation, which is precisely why `complete()` can hold row locks across
  several decrements.
- `domain` is a leaf containing types that cross boundaries in **both** directions —
  `ItemSnapshot` and `PopularItemRow` are handed *down* into the database access layer, so they
  cannot belong to an upper layer without inverting the dependency graph.

### Concurrency & Correctness

- Unchanged from week 1: `PESSIMISTIC_WRITE` row lock, sorted SKU lock order, clamp at zero,
  PostgreSQL default READ_COMMITTED (the row lock, not the isolation level, provides mutual
  exclusion).
- `InventoryService.decrement` is now `Propagation.MANDATORY`, converting week 1's implicit
  "caller must have a transaction" contract into an enforced one.
- Validated under load, not just functionally: 0 lost updates across 136 719 contended scans.

### Hopping-Window Analytics

- `ScanWindow` owns the queue, the counters and the window bounds; `DefaultAnalyticsService` owns
  the recompute policy; `PopularItemsRankingWriter` owns the write. Week 1 had all three in one
  class, which is what let the self-invocation bug hide.
- Recompute still fires every 500 scans behind a `ReentrantLock.tryLock()`, so a scan arriving
  mid-recompute skips rather than stalls.
- Window state is still in-process and resets on restart — an accepted limitation, and the obvious
  thing for a later distributed week to address.

### Known limitations (carried forward deliberately)

- **Analytics is still on the checkout hot path.** The 50-row `popular_items` rewrite commits
  inside whichever customer's scan crosses the slide boundary. Fixing this is easy and was
  deliberately deferred so this week's measurement stays interpretable.
- **Still one JVM and one database.** Layering organises a monolith; it does not let it scale out.
  `COMPLETE_TRANSACTION` tail latency under stress is the same row-lock ceiling week 1 hit, and
  breaking it needs the architectural changes later weeks explore — isolating inventory behind a
  service boundary, or replacing the lock with optimistic concurrency, a single-writer actor, or
  event sourcing.
- **`double` for money.** Fine for this contract; wrong for a real one.
