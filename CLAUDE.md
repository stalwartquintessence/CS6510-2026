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
- `week1-monolith/` — Week 1: layered Spring Boot monolith + PostgreSQL. Later weeks add sibling
  directories, each with their own README/ARCHITECTURE.md and build tooling.

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
"Stress mode" is just a larger `--stations` (e.g. `--stations=200 --duration=180`) — meaningful
mainly for weeks where scalability differences between architecture styles are the point.

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

There are no test classes in `week1-monolith/src` yet — `mvnw -DskipTests` is used deliberately,
and `mvnw test` will currently just run an empty test phase.

Drive it with the load client from the repo root (see above), pointing `--reportDir` at
`week1-monolith/reports` so results land alongside that week's other reports.

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

Each week's `README.md` documents how to build/run and that week's load-test results; each week's
`ARCHITECTURE.md` documents its architectural characteristics, trade-offs, and evidence — read
those for week-specific detail beyond what's summarized here.
