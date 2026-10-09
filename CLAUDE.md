# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A semester-long systems project: the **same API contract** (`spec/self-checkout-openapi.yaml`,
OpenAPI 3.0.3) is implemented from scratch each week using a different architecture style, in its
own top-level directory (`week1-monolith/`, `week2-…/`, …). The **same load-testing client**
(`load-client/`) and the same API contract are used to drive and evaluate every week's
implementation, so that performance/scalability differences observed week to week are attributable
to the architecture choice, not to a different test tool or a changed contract.

Do not modify `spec/self-checkout-openapi.yaml` or `load-client/` to make a given week's
implementation pass — the contract and client are fixed points; the implementation must conform to
them.

## Repo layout

- `spec/self-checkout-openapi.yaml` — the shared API contract every weekly implementation must satisfy.
- `load-client/` — zero-dependency Java load-testing client (JDK's `java.net.http.HttpClient` +
  hand-rolled JSON), used unmodified against every week's implementation.
- `mockserver/` — a deliberately uninteresting reference implementation of the contract (a few
  `ConcurrentHashMap`s behind `com.sun.net.httpserver`), useful for exercising the load client
  before a real week's implementation exists. Not an example of good architecture.
- `week1-monolith/` — Week 1: layered Spring Boot monolith + PostgreSQL.
- `week2-layered/` — Week 2: the same monolith with four explicit layers over a `domain` kernel,
  boundaries enforced by ArchUnit (`src/test/.../architecture/LayeredArchitectureTest.java`).
- `week3-pipeline/` — Week 3: week 2 with the popular-items analytics rebuilt as pipes and filters
  (Window → Rank → Enrich → Publish, one thread each, `BlockingQueue` pipes).
- `week4-services/` — Week 4: service-based. A multi-module Maven build: four domain services
  (catalog, transaction, inventory, analytics — one Spring Boot jar and JVM each) over one shared
  PostgreSQL, behind a REST gateway that calls them over gRPC.

Each week is a self-contained Maven project (week 4 is a multi-module one) with its own README/ARCHITECTURE.md, `mvnw`,
`docker-compose.yml` and `reports/`. Week N+1 starts as a copy of week N, and only the part under
study changes. Runtime tuning (Hikari 50, Tomcat 200, window 1000/500, catalog 2000 × 10 000) is
held constant so week-over-week numbers stay comparable.

## Common commands

### Load client (`load-client/`)

Requires a full JDK 21+ (`javac` must be available).

```bash
cd load-client
./build.sh
./run.sh --baseUrl=http://localhost:8080 --stations=10 --duration=60 --reportDir=../week1-monolith/reports
./run.sh --help
```

Key flags: `--baseUrl`, `--stations` (default 10), `--duration` (seconds, default 60),
`--minItems`/`--maxItems`, `--popularLimit`, `--requestTimeout`, `--verbose`, `--reportDir`.
"Stress mode" is just a larger `--stations`. Weeks 1–2 used `--stations=200 --duration=180`; the
week 3 assignment specifies `--stations=100 --duration=120`. Check each week's assignment text, and
re-run the previous week at the same load if it changed, so the comparison is like-for-like.

Run-to-run noise is large: the same jar has varied by ~25% between a run on a cold Docker VM and one
ten minutes later. Warm Docker up before measuring, compare against same-session baselines, and
don't report single-digit-percent differences as real effects.

### Mock reference server (`mockserver/`)

```bash
cd mockserver
./build.sh
./run.sh 8080 2000 10000 50   # port, catalogSize, stockPerItem, lowStockThreshold
```

### Week 1 monolith (`week1-monolith/`)

Requires JDK 17+ and Docker.

```bash
cd week1-monolith
docker compose up -d                 # start PostgreSQL (supermarket-pg, port 5432)
./mvnw -DskipTests package           # build
java -jar target/week1-monolith-1.0.0.jar     # run (or: ./mvnw spring-boot:run)
docker compose down -v               # reset stock for a fresh run (re-seeds on empty DB)
```

There are no test classes in `week1-monolith/src` — `mvnw test` just runs an empty test phase.

### Weeks 2 and 3 (`week2-layered/`, `week3-pipeline/`)

Same commands as week 1, from the week's own directory. Postgres host ports differ so containers can
coexist: week 1 `supermarket-pg` on 5432, week 2 `supermarket-pg-week2` on 5433, week 3
`supermarket-pg-week3` on 5434. The app is always on 8080, so run one week's server at a time.

`./mvnw test` is meaningful from week 2 on: week 2 has 7 ArchUnit layer rules; week 3 adds 3
pipeline rules (`PipelineArchitectureTest`) and 5 threaded pipeline tests
(`ScanAnalyticsPipelineTest`), 15 in total. If a build produces a tiny jar or "Unable to find main
class", `target/` is stale — use `./mvnw clean package`.

The catalog seeder is a `CommandLineRunner` and finishes just after the port opens; wait for
`Seeded catalog: 2000 items` in the log before starting load.

Drive any week with the load client from the repo root (see above), pointing `--reportDir` at that
week's `reports/` directory.

Correctness invariant to check after any load run (independent of any performance number): for
every SKU, `initial_stock - final_stock` must equal completed-transaction units sold, and final
stock must never go negative. The SQL to check this against the running container is in
`week1-monolith/README.md`.

## Architecture notes

### The contract's non-negotiables (apply to every week)

- Stock is decremented only at transaction **completion**, never at scan time — scanning just adds
  to the basket.
- Every client-facing endpoint is synchronous, regardless of what an implementation does
  internally (an event-driven or orchestration week may use queues/events internally, but the
  external contract never changes).
- `GET /analytics/popular-items` reports a **hopping window**: the most recent `windowSize` scans
  (spec default 1000), recomputed every `slideInterval` scans (spec default 500), with
  `windowStart`/`windowEnd` in the response so the window bounds are auditable.
- The load client's item-popularity sampling is intentionally Zipf-weighted
  (`load-client/src/ItemSampler.java`), not uniform — a small number of SKUs get scanned
  disproportionately often, which is what makes popular-items and hot-row contention observable.

### The concurrency invariant every implementation must hold

With many stations completing transactions concurrently against shared inventory, a naive
"read stock, check, then write stock-1" done as separate steps allows two stations to both
successfully buy the last unit. This is easy to get right by accident in a monolith (one process,
one local transaction) and much easier to get wrong once inventory becomes its own service
reachable only over the network (service-based/microservices weeks). Whatever mechanism a given
week uses (row locks, optimistic concurrency, single-writer actor, event sourcing with
conflict resolution, etc.), it must be validated against this invariant under load, not just
functionally.

### Week 1 monolith internals (`week1-monolith/`)

Classic **controller → service → repository** Spring Boot layering, one JVM, one PostgreSQL
database (`ddl-auto=update` creates `items`, `transactions`, `transaction_items`, `popular_items`
from the JPA entities):

- `controller/` — `CatalogController`, `TransactionController`, `InventoryController`,
  `AnalyticsController`, `GlobalExceptionHandler`.
- `service/` — `CatalogService`, `TransactionService`, `InventoryService`, `AnalyticsService`.
- `repository/` — `TransactionRepository`, `InventoryRepository`, `PopularItemRepository`.
- `model/` — JPA entities (`InventoryItem`, `Transaction`, `TransactionItem`, `PopularItem`,
  `TransactionStatus`).
- `dto/Dtos.java` — all request/response records in one file.
- `config/` — `DatabaseConfig`, `DataInitializer` (seeds the catalog: 2000 items × 10,000 units,
  configured via `supermarket.catalog.*` in `application.properties`).

**Inventory concurrency control**: on `POST /transactions/{id}/complete`, `TransactionService`
sorts the basket's SKUs before touching inventory (a stable lock-acquisition order so two
concurrent completions can never deadlock on each other), then for each SKU calls
`InventoryService.decrement`, which loads the row with `@Lock(PESSIMISTIC_WRITE)`
(`SELECT … FOR UPDATE`) inside the completion's `@Transactional` boundary and decrements it,
clamped at zero. Every decrement of a given SKU serializes on that row lock, which is what makes
the "two stations both buy the last unit" race impossible — and is also the mechanism responsible
for `COMPLETE_TRANSACTION` tail latency blowing up under high station counts (documented with
concrete before/after numbers in `week1-monolith/ARCHITECTURE.md`).

**Analytics (hopping window)**: `AnalyticsService` keeps the most recent `windowSize` scanned SKUs
in a `ConcurrentLinkedQueue`, with a global scan counter in an `AtomicLong`. Every
`slideInterval` scans, it recomputes the top-N ranking under a `ReentrantLock.tryLock()` and
rewrites the `popular_items` table with the `windowStart`/`windowEnd` sequence numbers. The
recompute uses `tryLock()` rather than blocking so a scan that arrives mid-recompute never stalls
on it — it just skips triggering another recompute.

Connection pool (HikariCP, 50 connections) and Tomcat thread pool (200) are the real concurrency
ceiling of this implementation; both are tuned in `application.properties` alongside the
catalog-seeding and analytics-window defaults (`supermarket.catalog.*`,
`supermarket.inventory.low-stock-threshold`, `supermarket.analytics.*`).

### Week 2 layered internals (`week2-layered/`)

Packages `api/` (controllers, DTOs, mappers, the only HTTP code), `transaction/` (checkout, plus
`catalog/` and `inventory/` submodules), `analytics/`, `persistence/` (DAO ports `ItemDao`,
`TransactionDao` and `PopularItemsDao`; entities; package-private Spring Data repositories) and
`domain/` (`Basket`, `ItemSnapshot`, domain errors; depends on nothing). Interfaces sit at every
boundary, with package-private implementations. Concurrency control is identical to week 1, except
that `InventoryService.decrement` is `Propagation.MANDATORY`.

### Week 3 pipeline internals (`week3-pipeline/`)

Only `analytics/` differs from week 2:

- `analytics/pipeline/` is a generic framework (`Pipe`, `Stage`, `Filter`, `Sink`) that depends
  only on the JDK.
- The concrete stages `WindowFilter`, `RankFilter`, `EnrichFilter` and `PublishSink`, plus their
  immutable message records, are package-private in `analytics/`.
- `ScanAnalyticsPipeline` (`SmartLifecycle`, phase 0) wires the pipes and owns the threads.
- `DefaultAnalyticsService.recordScan` is a non-blocking `offer()`; `getPopular` serves the
  in-memory `PublishedRanking`.
- The pipe after Window is conflating (capacity 1, latest wins), so a slow DB write can never
  back up into the scan pipe.

Each week's `README.md` documents how to build/run and that week's load-test results; each week's
`ARCHITECTURE.md` documents its architectural characteristics, trade-offs, and evidence — read
those for week-specific detail beyond what's summarized here.

### Week 4 service-based internals (`week4-services/`)

Different shape from weeks 1–3: **multi-module**, not one jar. Build and run from `week4-services/`:

```bash
docker compose up -d            # supermarket-pg-week4 on host port 5435; db/init.sql creates the schema
./mvnw -DskipTests package      # each service jar is target/<name>-1.0.0-exec.jar (plain jar kept for tests)
./run-local.sh                  # five JVMs; logs/<service>.log; stop with ./stop-local.sh
./mvnw test                     # 22 tests incl. architecture-tests (ArchUnit across all services)
```

- Modules: `shared-db` (domain kernel, entities, DAOs — linked into every service via
  `@Import(SharedDatabaseConfig.class)`), `contracts` (`supermarket.proto` + generated stubs; `protoc` is
  fetched by Maven), `service-support` (gRPC server lifecycle, `DomainErrorInterceptor`),
  `catalog-service` :9101, `transaction-service` :9102, `inventory-service` :9103,
  `analytics-service` :9104, `gateway` :8080 (the only HTTP surface and the only gRPC client; no DB).
- Services never call each other. The one cross-service channel is the `scan_log` table: the
  transaction service appends a row per scan inside the scan's DB transaction; the analytics service
  tails it (`ScanLogSource`, 250 ms settle delay to tolerate sequence-vs-commit order) into the
  week 3 pipeline. Stock decrement stays in the transaction service (`StockLedger`) so the sale is
  still one local ACID transaction — it is *not* in inventory-service, which only reports.
- The schema is `db/init.sql` (services run `ddl-auto=none`), not Hibernate-generated; it deliberately
  mirrors the weeks 1–3 tables, including no index on `transaction_items.tx_id`.
- Table-write ownership is enforced by ArchUnit rules in `architecture-tests`, not by convention.
- Tuning held constant: Tomcat 200 on the gateway; 200 gRPC worker threads + Hikari 50 on the
  transaction service; other services are small.
- Postgres host port 5435. Run `docker compose down -v` and restart the services between measured runs
  (the catalog service seeds on an empty DB; `run-local.sh` skips services whose pid file is live, so
  run `./stop-local.sh` first or the catalog will not be re-seeded).
