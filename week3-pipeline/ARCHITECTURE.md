# Self-Checkout Pipeline Architecture Analysis

> Latency figures come from the two committed reports in `reports/` (default
> `report-20261005-031611.json`, stress `report-20261005-030948.json`) and the
> same-session week 2 baselines in `week2-layered/reports/`
> (`report-20261005-031152.json`, `report-20261005-031436.json`). Hardware is a
> single M1 Pro laptop on battery power, with PostgreSQL in Docker on the same machine.

## What changed from week 2

Week 2 was a layered monolith with enforced boundaries. Week 3 keeps all of it — same layers, same
seven ArchUnit rules, same checkout code, same row-lock concurrency control, same tuning — and
rebuilds **one feature** in the pipes-and-filters style: the hopping-window popular-items analytics.

The assignment notes that the application as a whole is not a natural pipeline, and that is right.
Checkout is request/response against shared mutable inventory, with each step depending on the
previous one's result and the client waiting for every answer. The analytics is different: it is a
one-way stream of scans that are windowed, counted, ranked, labelled and published, where no
producer waits for the result. That is the shape pipes and filters fit.

### Week 2's analytics, for contrast

Every scan called `DefaultAnalyticsService.recordScan()` on the Tomcat request thread. That method
appended to a `ConcurrentLinkedQueue`, bumped an `AtomicLong` sequence and an `AtomicInteger` size
counter, and trimmed the queue. On every 500th scan, the **customer's request thread** then did the
whole recompute behind a `ReentrantLock.tryLock()`, inside that customer's scan transaction:

- snapshot the window;
- count and sort;
- issue 50 `findBySku` queries;
- rewrite the 50-row `popular_items` table;
- publish the window bounds to three separate volatiles.

### Week 3's analytics

```
recordScan ─▶ [scans] ─▶ Window ─▶ [snapshots] ─▶ Rank ─▶ [rankings] ─▶ Enrich ─▶ [rows] ─▶ Publish
             ingress                conflating             bounded               bounded
```

`recordScan()` is now a single non-blocking `offer()` onto a queue. Everything else happens on four
dedicated threads, one per filter, which communicate only through bounded `BlockingQueue` pipes.

## Architectural Characteristics

| Characteristic | Concrete requirement |
| -------------- | -------------------- |
| **Modularity** | Each analytics step is an independent filter that can be replaced, re-ordered or removed without touching the others; enforced by tests |
| **Responsiveness** | A customer's scan never waits for analytics work, and never fails because of it |
| **Consistency** | Inventory: no double-sells, stock never negative. Analytics: no lost or double-counted slide boundaries; the ranking and its window bounds are always read together |
| **Fault isolation** | A failure in any analytics stage affects analytics only, and only for one window |
| **Testability** | Each stage is a plain object testable without Spring or a database; the whole pipeline is testable with real threads against fakes |
| **Performance** | Endpoint latency under 1 s p95 at the default load (10 stations) |
| **Scalability** | Serve 100 concurrent checkout stations without errors |

## Top 3 Prioritized Characteristics

### 1. Modularity (independent filters connected only by pipes)

- **Why:** This is the week's assignment, and it is what pipes and filters is *for*. Week 2's
  analytics was one class doing windowing, counting, sorting, catalog lookup, persistence and
  publication, with shared mutable state guarded by atomics and a lock.
- **How:**
  - A generic framework in `analytics.pipeline`: `Pipe`, `Stage`, `Filter`, `Sink`, under 300 lines
    including comments, with no Spring or application types.
  - Four concrete stages, each holding a reference only to its input and output pipes.
  - Immutable records as the messages (`WindowSnapshot`, `Ranking`, `EnrichedRanking`).
  - `ScanAnalyticsPipeline` is the one place that knows the topology.
  - Three new ArchUnit rules enforce this:
    1. the framework depends only on the JDK;
    2. no stage depends on another stage;
    3. Window and Rank are pure, depending only on the JDK, the framework and message records.
- **Trade-off:** More types and four extra threads for a feature that was two classes (about 220
  lines) in week 2. It pays
  off in what each stage no longer needs. Because exactly one thread owns each stage, the window is
  a plain `ArrayDeque` and a `long`, where week 2 needed a `ConcurrentLinkedQueue`, an
  `AtomicInteger`, an `AtomicLong` and a `ReentrantLock`. The Enrich cache is a plain `HashMap`.
  The pipes are the only shared mutable state in the analytics.
- **Evidence:** 3 new rules, all passing, each checked to fail:
  - an `EnrichFilter` field on `RankFilter` trips "filters communicate only through pipes" and
    "Window and Rank are pure";
  - an `ItemDao` field on `WindowFilter` trips the purity rule;
  - a domain-type field on `Pipe` trips "the framework is generic".

  `jstack` on the running server shows the four threads `pipeline-window`, `pipeline-rank`,
  `pipeline-enrich` and `pipeline-publish`.

### 2. Responsiveness and fault isolation (checkout never waits on analytics)

- **Why:** The analytics is a secondary feature, so a slow database write or a failed catalog lookup
  in it should never delay or fail a customer's scan. In week 2 it could do both, because the
  recompute and its writes ran inside the scan's request and transaction.
- **How:**
  - **Non-blocking entry.** `recordScan` `offer()`s into a 65 536-slot `LinkedBlockingQueue`.
    Producers and the consumer lock separately, which matters with up to 200 Tomcat threads
    offering. If the queue were ever full, the scan would be dropped from analytics and counted;
    the customer's scan still succeeds.
  - **Conflating pipe after Window.** Each snapshot is a complete window, so an unconsumed one is
    worthless once a newer one exists. The capacity-1 conflating pipe replaces it rather than
    blocking. As a result, back-pressure from a slow Publish can never reach Window, and so can never
    fill the scan pipe and start losing raw scans. This is the pipeline form of week 2's "tryLock,
    skip if busy". Downstream of Rank, pipes block normally, since at 2 messages/second they are
    never full.
  - **Survivable stages.** A `RuntimeException` while handling one message is logged and counted,
    and the stage moves on. A failed catalog lookup costs one window, and the next window, 500 scans
    later, retries.
  - **Own transactions.** The 50-row rewrite now commits in Publish's own transaction, not in a
    customer's.
- **Trade-off:** The ranking is now *eventually* consistent with the scans. A `GET` immediately
  after the 500th scan may still return the previous window for a few milliseconds. Under extreme
  overload, analytics would degrade by skipping windows (and, only if a stage thread died, by
  dropping scans) rather than slowing checkout. That is the right priority for a popularity
  feature, but it is a real semantic change. Week 2's ranking was always computed synchronously
  from every scan.
- **Evidence:**
  - Shutdown counters on every load run show **0 scans dropped, 0 snapshots superseded**, and
    Window handled exactly the client's scan count (65 350 default, 100 093 stress).
  - The pipeline test `aSlowSinkSupersedesSnapshotsInsteadOfDroppingScans` makes the database write
    take 50 ms and feeds 20 000 scans. Snapshots are superseded, no scans are dropped, and the final
    published window is exactly 19 000..20 000.
  - `aFailedLookupSkipsOneWindowButTheStageKeepsRunning` shows the Enrich thread surviving a thrown
    exception.

### 3. Consistency (inventory unchanged, analytics tightened)

- **Why:** No double-sells remains the business-critical invariant, and moving work across threads
  is exactly the kind of change that quietly breaks consistency somewhere.
- **How:**
  - **Inventory** is untouched: decrement only at completion, a `PESSIMISTIC_WRITE` row lock inside
    the completion transaction, sorted SKU lock order, clamp at zero.
  - **Analytics** gets stronger guarantees than week 2 had:
    1. **Sequence numbers are assigned by Window, in pipe arrival order.** Week 2 incremented the
       sequence and appended to the queue as two separate atomic steps on 200 threads, so the
       window's contents and the number of the scan that triggered a recompute could disagree
       slightly. With one owner, the arrival order *is* the global order. No boundary can be skipped
       or doubled, and the bounds stay `windowStart = max(0, windowEnd − 1000)`.
    2. **Rewrites are strictly sequential.** Week 2 released its recompute lock *before* the scan's
       transaction committed, so two rewrites of `popular_items` could overlap. One Publish thread
       makes that impossible.
    3. **Reads are atomic.** Week 2's `GET` read rows from the table and bounds from three
       volatiles, so it could return new rows with old bounds. Publish now swaps in one immutable
       `PublishedRanking(windowStart, windowEnd, computedAt, rows)`, and only after the table write
       has committed. `GET` reads that single reference.
- **Trade-off:** `GET` serves from memory, falling back to the table only before the first publish
  after a restart, with bounds 0/0, as week 2 did. The table remains the durable record but is no
  longer on the read path.
- **Evidence:**
  - After every run, including the discarded one: **0 lost updates, 0 negative-stock rows**.
  - Default: 65 350 units decremented = 65 350 sold = the client's scan count.
  - Stress: `SKU-000001` floored at 0 under 12 463 units of demand against 10 000 supply.
  - Published bounds after each run were exact multiples of 500 (49 500..50 500, 99 000..100 000).
  - `theWindowHopsAndForgetsScansOlderThanWindowSize` checks hop bounds and eviction precisely.

## Load Test Results

Week 2 was re-run in the same session at both loads, because the assignment's stress mode
(100 × 120) differs from the 200 × 180 that weeks 1–2 originally reported.

| | Week 2 default | Week 3 default | Week 2 stress | Week 3 stress |
| --- | --- | --- | --- | --- |
| Stations × seconds | 10 × 60 | 10 × 60 | 100 × 120 | 100 × 120 |
| Throughput (tx/sec) | 97.8 | 104.0 | 81.7 | 78.9 |
| START p95 / p99 | 5.2 / 7.6 ms | 4.3 / 6.1 ms | 74 / 128 ms | 88 / 162 ms |
| SCAN p95 / p99 | 10.5 / 15.3 ms | 10.2 / 13.8 ms | 84 / 122 ms | 98 / 144 ms |
| COMPLETE p95 / p99 | 74.0 / 119.5 ms | 65.4 / 101.0 ms | 1.64 / 2.89 s | 1.74 / 2.97 s |
| COMPLETE max | 288 ms | 342 ms | 7.47 s | 7.79 s |
| Errors | 0 | 0 | 0 | 0 |

The 1 s p95 SLA holds comfortably for every operation at the default load. Under stress it holds for
START and SCAN and is exceeded for COMPLETE, the same verdict as weeks 1 and 2, for the same reason.

**Interpretation.** Week 3 is about 6% faster at default load and 3–5% slower under stress. Neither
is a real effect:

- **The recompute was already rare.** It ran on 1 scan in 500 (0.2%), which is beyond the p99, so
  taking it off the request thread could at most trim the extreme tail. It couldn't move p95/p99.
- **COMPLETE never touched analytics.** Its p95/p99 moved by similar percentages in both
  directions, which means those percentages are measuring noise.
- **The noise is large.** The same week 3 jar was run twice for the default load in this session.
  The first run, made immediately after Docker Desktop cold-started, gave 79.0 tx/sec (START p95
  7.1 ms, SCAN p95 12.9 ms, COMPLETE p95 113.1 / p99 188.4 ms, 0 errors, invariant held, 50 520
  scans, 101 windows, 0 dropped). The second, ten minutes later on a warm Docker VM, gave
  104.0 tx/sec. The first was discarded as an environment artifact. It is not among the two
  submitted reports, and its numbers are recorded here so nothing is hidden. A 24% swing between two
  runs of identical code dwarfs every week-over-week difference in the table.

The correct conclusion is that **the pipeline is free at runtime**. Its benefits are the structural
ones above. The throughput ceiling is still the pessimistic row lock on Zipf-hot SKUs during
`COMPLETE_TRANSACTION`, which this week deliberately did not touch.

Popular-items ranking is stable across weeks and runs: `SKU-000001`, `SKU-000002`, then
`SKU-000003` or `SKU-000004`.

## Design Decisions

### The pipes

| Pipe | Implementation | Why |
| ---- | -------------- | --- |
| scans | `LinkedBlockingQueue`, 65 536, non-blocking `offer` | Many producers (request threads) that must never wait; separate put/take locks |
| snapshots | `ArrayBlockingQueue(1)`, conflating | Each message is a complete window; latest wins, so Window never blocks |
| rankings, rows | `ArrayBlockingQueue(4)`, blocking `put` | Ordinary back-pressure; effectively never full at 2 msg/s |

The conflating `put` is `while (!queue.offer(x)) queue.poll();`. That is safe because Window is the
only producer on that pipe: if Rank takes the stale snapshot between the failed offer and the poll,
the poll finds nothing and the loop retries the offer.

### Stage lifecycle

- `ScanAnalyticsPipeline` is a `SmartLifecycle` in **phase 0**. That starts it before the embedded
  web server, so no scan reaches a pipeline that isn't running, and stops it after Tomcat has
  drained, so no scan arrives at a stopping pipeline.
- Shutdown is cooperative. Each stage polls its input with a 100 ms timeout and checks a volatile
  `running` flag, and `stop()` joins each thread for up to 2 s. A stage is interrupted only if it
  overruns, which avoids interrupting a thread mid-way through a JDBC call. Spring stops lifecycle
  beans before destroying the DataSource, so no stage is mid-transaction when the pool closes.
- The Enrich cache fills lazily. The catalog is seeded by a `CommandLineRunner` that runs *after*
  the pipeline starts. An unknown SKU falls back to the SKU itself as its name but is not cached, so
  a scan that somehow precedes seeding can't poison the cache.

### Transactions on a non-request thread

`PopularItemsRankingWriter.rewrite` changed from `@Transactional(propagation = REQUIRED)` (joining
the scan's transaction) to plain `@Transactional`. On the Publish thread there is no outer
transaction, so it opens and commits its own. The call still goes through the Spring proxy, because
`deleteAllInBatch` refuses to run outside a transaction. Enrich's `findBySku` runs in Spring Data's
own read-only transaction per call. The pipeline holds at most two pool connections, both briefly.

### Concurrency & Correctness (checkout)

Unchanged from weeks 1 and 2:

- `PESSIMISTIC_WRITE` row lock, sorted SKU lock order, clamp at zero;
- `InventoryService.decrement` is `Propagation.MANDATORY`;
- PostgreSQL READ_COMMITTED (the row lock, not the isolation level, provides mutual exclusion).

### Known limitations

- **Window state is still in memory** and resets on restart. A durable or distributed pipeline (a
  log such as Kafka between stages) is the obvious next step, and a natural fit for a later
  event-driven week.
- **Analytics counts scans from transactions that later roll back**, because `recordScan` is called
  before the scan's transaction commits. This is the same as week 2. Offering from a
  `TransactionSynchronization.afterCommit` hook would fix it.
- **Pipe order is offer order, not commit order.** Two concurrent scans enter the window in
  whichever order their request threads reached `offer()`. This is equivalent to week 2's
  behaviour, and immaterial for a popularity ranking.
- **Thread-per-stage** is the simplest pipe implementation and fine for four low-rate stages. A
  high-volume pipeline would batch messages, or use a ring buffer (LMAX Disruptor) to avoid a
  queue hand-off per scan.
- **Still one JVM and one database**, and `COMPLETE_TRANSACTION` tail latency under stress is the
  same row-lock ceiling as before. Breaking it needs the changes later weeks explore: inventory
  behind a service boundary, optimistic concurrency, a single-writer actor, or event sourcing.
- **`double` for money.** Fine for this contract; wrong for a real one.
