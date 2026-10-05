# Week 3 — Pipeline Architecture (pipes and filters)

The same self-checkout contract (`spec/self-checkout-openapi.yaml`), driven by the same unmodified
`load-client/`. The checkout flow itself is not a natural pipeline — start, scan and complete are
synchronous request/response calls against shared inventory — but the **windowed popular-items
analytics** is: a stream of scans flows in, gets windowed, counted, ranked, labelled and written
out. That one feature has been rebuilt as a pipeline of threaded filters connected by pipes.
Everything else is week 2's layered architecture, unchanged.

## The pipeline

The analytics feature is a **4-stage pipeline**:

```
POST /transactions/{id}/items
  │  recordScan(sku): non-blocking offer, the request thread never waits
  ▼
[scans]      LinkedBlockingQueue<String>, capacity 65 536
  ▼
Window   ── assigns the global scan sequence number, keeps the last 1000 SKUs,
  │         and every 500 scans emits a snapshot of the window with its bounds
[snapshots]  conflating queue, capacity 1 — a newer snapshot replaces an unconsumed one
  ▼
Rank     ── counts the SKUs in the snapshot and sorts them into a top-50 ranking
  │
[rankings]   ArrayBlockingQueue, capacity 4
  ▼
Enrich   ── attaches item names via the catalog (with a stage-private cache)
  │
[rows]       ArrayBlockingQueue, capacity 4
  ▼
Publish  ── rewrites the popular_items table in its own transaction, then publishes
            the ranking + window bounds for GET /analytics/popular-items
```

| Filter | Purpose |
| ------ | ------- |
| **Window** | Ingests every scanned SKU in arrival order, numbers it with the global scan sequence, holds the most recent `windowSize` (1000) SKUs, and every `slideInterval` (500) scans emits a `WindowSnapshot(windowStart, windowEnd, skus)`. |
| **Rank** | Pure transformation: counts the snapshot's SKUs and sorts them by scan count (descending, SKU ascending on ties), keeping the top 50. |
| **Enrich** | Resolves each SKU to its catalog name via `ItemDao`, caching names because they never change. |
| **Publish** | Sink: persists the ranking to `popular_items`, then atomically swaps in the published ranking that the API serves. |

**How they are connected:** each filter runs on its own dedicated thread (`pipeline-window`,
`pipeline-rank`, `pipeline-enrich`, `pipeline-publish`), and the pipes between them are Java
`BlockingQueue`s. Scans enter through a bounded `LinkedBlockingQueue` that request threads
`offer()` into without blocking. A capacity-1 *conflating* queue (latest snapshot wins) follows
Window, so a slow database write downstream can never back up into the scan pipe. Bounded
`ArrayBlockingQueue`s with blocking hand-off connect the remaining stages. Filters never reference
each other — only the pipe they read from and the pipe they write to — and that is enforced by an
ArchUnit rule.

### Where the code is

| Package | Contents |
| ------- | -------- |
| `com.supermarket.analytics.pipeline` | The generic mechanism: `Pipe`, `Stage`, `Filter`, `Sink`. Depends on nothing but the JDK. |
| `com.supermarket.analytics` | The four concrete stages (`WindowFilter`, `RankFilter`, `EnrichFilter`, `PublishSink`), the immutable messages that flow between them (`WindowSnapshot`, `Ranking`, `EnrichedRanking`, `PublishedRanking`), and `ScanAnalyticsPipeline`, which wires the pipes and runs the threads. `DefaultAnalyticsService` is now a thin adapter onto the pipeline. |

The rest of the application — `api/`, `transaction/`, `persistence/`, `domain/` — is week 2's code.
`DefaultTransactionService.scan()` still calls `analyticsService.recordScan(sku)`; it just returns
immediately now.

## Running it

Requires JDK 17+ and Docker.

```bash
cd week3-pipeline
docker compose up -d                       # PostgreSQL (supermarket-pg-week3, host port 5434)
./mvnw test                                # ArchUnit rules + pipeline tests
./mvnw -DskipTests package
java -jar target/week3-pipeline-1.0.0.jar
```

Postgres uses host port **5434**, so weeks 1 (5432) and 2 (5433) can keep their containers. The app
itself is on 8080 like every week, so run one week's server at a time.

Wait for `Seeded catalog: 2000 items` in the log before driving load. On startup you should also see
`Analytics pipeline started: [window, rank, enrich, publish]`. On shutdown, each stage logs how many
messages it handled and the pipeline logs dropped scans and superseded snapshots.

```bash
# in another shell
cd ../load-client && ./build.sh
./run.sh --baseUrl=http://localhost:8080 --reportDir=../week3-pipeline/reports                              # default
./run.sh --baseUrl=http://localhost:8080 --stations=100 --duration=120 --reportDir=../week3-pipeline/reports # stress
```

`docker compose down -v` resets stock for a fresh run.

## Tests

`./mvnw test` runs 15 tests:

- **7 layer rules** carried over from week 2 (`LayeredArchitectureTest`).
- **3 pipeline rules** (`PipelineArchitectureTest`):
  1. the `pipeline` framework depends only on the JDK and itself;
  2. no stage depends on another stage — they meet only through pipes;
  3. `WindowFilter` and `RankFilter` are pure: they may use only the JDK, the framework and message records.

  Each was checked to actually fail: giving `RankFilter` an `EnrichFilter` field, `WindowFilter` an
  `ItemDao` field, or `Pipe` a domain-type field trips the expected rule(s).
- **5 pipeline tests** (`ScanAnalyticsPipelineTest`): real threads and pipes against in-memory DAO fakes —
  - the first full window is published with bounds 0..1000 and the right ranking;
  - the window hops and forgets old scans (2500 scans → bounds 1500..2500);
  - a slow sink conflates snapshots instead of dropping scans;
  - a failed catalog lookup skips one window but the stage keeps running;
  - the service falls back to the table before the first publish.

## Results (this machine)

Week 3's two submission reports are in `reports/`:

- `report-20261005-031611.json` — default (10 stations, 60 s)
- `report-20261005-030948.json` — stress (100 stations, 120 s)

The assignment's stress mode (100 stations × 120 s) differs from the 200 × 180 that weeks 1–2 used,
so week 2 was **re-run in the same session at both loads** to give a like-for-like baseline:
`week2-layered/reports/report-20261005-031152.json` (default) and `report-20261005-031436.json`
(100 × 120). Same laptop, both on battery power, each run on a freshly reset database.

### Default mode — 10 stations, 60 s

| Metric | Week 2 | Week 3 |
| ------ | ------ | ------ |
| Throughput | 97.8 tx/sec | **104.0 tx/sec** |
| Items/sec | 1 020.5 | **1 087.0** |
| START p95 / p99 | 5.2 / 7.6 ms | **4.3 / 6.1 ms** |
| SCAN p95 / p99 | 10.5 / 15.3 ms | **10.2 / 13.8 ms** |
| COMPLETE p95 / p99 | 74.0 / 119.5 ms | **65.4 / 101.0 ms** |
| Errors | 0 | **0** |

### Stress mode — 100 stations, 120 s

| Metric | Week 2 | Week 3 |
| ------ | ------ | ------ |
| Throughput | 81.7 tx/sec | **78.9 tx/sec** |
| Items/sec | 869.8 | **826.4** |
| START p95 / p99 | 74 / 128 ms | **88 / 162 ms** |
| SCAN p95 / p99 | 84 / 122 ms | **98 / 144 ms** |
| COMPLETE p95 / p99 | 1.64 / 2.89 s | **1.74 / 2.97 s** |
| Errors | 0 | **0** |

**The honest reading: performance is unchanged.** Week 3 is about 6% ahead at default load and
3–5% behind under stress, so the differences run in both directions. They are also smaller than
the noise demonstrated in this very session: the *same week 3 jar* produced 79.0 tx/sec in a run
made right after Docker Desktop started cold, and 104.0 tx/sec ten minutes later. That cold run was
discarded for that reason. It isn't among the two reports, and its numbers are
recorded in [ARCHITECTURE.md](ARCHITECTURE.md) for transparency.

That outcome is expected. Week 2 already skipped the recompute when one was in progress, and the
recompute ran on only 1 scan in 500, which is below the p99. Moving it off the request
thread therefore can't show up in p95/p99. `COMPLETE_TRANSACTION`, which never touched analytics, is
still dominated by the pessimistic row lock on Zipf-hot SKUs, exactly as in weeks 1 and 2. The
pipeline's benefits are structural, not throughput: see [ARCHITECTURE.md](ARCHITECTURE.md).

The popular-items ranking is stable across both weeks and all runs — `SKU-000001`, `SKU-000002`,
then `SKU-000003`/`SKU-000004` — as it should be, since analytics is a feature, not an
architectural property.

### Correctness invariant

Checked against the database after every run, including the discarded one:

- **Default:** 0 lost updates, 0 negative-stock rows. 65 350 units decremented = 65 350 units sold
  = the client's reported scan count, exactly.
- **Stress:** 0 lost updates, 0 negative-stock rows. `SKU-000001` saw demand of 12 463 units against
  10 000 supply and correctly floored at 0 — legitimate demand-over-supply (the Zipf sampler sends
  ~12 % of scans to rank 1), not a concurrency defect.
- **Pipeline accounting:** on shutdown the Window stage had handled exactly as many scans as the
  client sent (65 350 default, 100 093 stress). Every window went through all four stages (130 and
  200), with **0 scans dropped and 0 snapshots superseded** in every run.

```bash
docker exec -i supermarket-pg-week3 psql -U supermarket -d supermarket <<'SQL'
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
