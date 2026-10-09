# Self-Checkout Service-Based Architecture Analysis

> Latency figures come from the two committed reports in `reports/` and the same-session week 3
> baselines in `week3-pipeline/reports/` (named in the Load Test Results section). Hardware is a
> single M1 Pro laptop on battery power, with PostgreSQL in Docker on the same machine and every
> service a separate JVM on that same machine — so there is no real network between services, and
> the cost of the extra hop measured here is a lower bound.

## The services

| Service | Port | Business function | Writes | gRPC contract |
| ------- | ---- | ----------------- | ------ | ------------- |
| **catalog-service** | 9101 | The item catalog; seeds it on first run | `items` (insert) | `CatalogService.ListItems` |
| **transaction-service** | 9102 | Checkout: start, scan, complete, status; the stock decrement | `transactions`, `transaction_items`, `items.stock`, `scan_log` | `TransactionService.Start / Scan / Complete / GetStatus` |
| **inventory-service** | 9103 | Stock reporting (low-stock alerts) | — (read-only) | `InventoryService.GetLowStock` |
| **analytics-service** | 9104 | Windowed popular items (the week 3 pipeline) | `popular_items` | `AnalyticsService.GetPopularItems` |
| gateway *(not a domain service)* | 8080 | The REST contract, translated to gRPC. No database. | — | — |

Four domain services, one gateway, one PostgreSQL. Each is its own Maven module, its own Spring Boot
jar and its own JVM.

```
                                   ┌──────────────────────────┐
  load client ──HTTP/JSON──▶       │ gateway :8080            │
  (unchanged)                      │ REST → gRPC, no database │
                                   └───┬────────┬────────┬────┘
                          gRPC         │        │        │         gRPC
                  ┌────────────────────┘        │        └─────────────────────┐
                  ▼                  ▼          ▼                              ▼
        ┌──────────────────┐ ┌─────────────────────┐ ┌──────────────────┐ ┌───────────────────┐
        │ catalog :9101    │ │ transaction :9102   │ │ inventory :9103  │ │ analytics :9104   │
        └────────┬─────────┘ └──────────┬──────────┘ └────────┬─────────┘ └─────────┬─────────┘
                 │ JDBC                 │ JDBC                │ JDBC                │ JDBC
                 └──────────────────────┴──────────┬──────────┴─────────────────────┘
                                                   ▼
                                  ┌──────────────────────────────────┐
                                  │ PostgreSQL (one shared database) │
                                  │ items · transactions ·           │
                                  │ transaction_items · scan_log ·   │
                                  │ popular_items                    │
                                  └──────────────────────────────────┘
```

No arrow runs between two domain services. The only paths between them are *through the database*.

## What changed from week 3

Week 3 was one process with the analytics rebuilt as a pipeline. Week 4 cuts the whole application
along **business functions** into separately deployable services, and puts them behind a gateway so
the unchanged load client (and the unchanged OpenAPI contract) still sees one server on port 8080.
The code inside each service is week 3's code — the same DAOs, the same row-locked decrement, the
same pipeline — moved, not rewritten. What is genuinely new:

1. **Process boundaries.** Five jars instead of one.
2. **The gateway and gRPC.** REST stops at the gateway; behind it the contracts are in
   `contracts/src/main/proto/supermarket.proto`.
3. **A shared database access library** (`shared-db`) linked into every service.
4. **The `scan_log` table**, which replaces week 3's in-process `recordScan()` call and is the only
   channel between the transaction and analytics services.
5. **A hand-written schema** (`db/init.sql`) instead of Hibernate's `ddl-auto=update`, because five
   processes must not race to create tables.

## How the services are coupled: the database, and only the database

The assignment's central instruction is that *"service coupling effectively occurs at the database
access layer"*, and that is the design decision the whole week hangs on.

### Shared database access logic: a library, not a service

`shared-db` holds the domain kernel (`Basket`, `ItemSnapshot`, domain errors), the JPA entities, the
DAO ports (`ItemDao`, `TransactionDao`, `PopularItemsDao`, `ScanLogDao`) and their implementations.
Every service links it with a single line, `@Import(SharedDatabaseConfig.class)`. This was chosen over
the two alternatives:

| Option | Why not |
| ------ | ------- |
| **Each service writes its own SQL/entities** | Five copies of the `items` mapping to drift apart. A column rename would be five separate edits and five chances to miss one. |
| **A "data service" that owns the DB and everyone calls it** | Re-introduces exactly the service-to-service calls the assignment says to avoid, and turns every query into an RPC. It also makes the stock decrement impossible to do in the same transaction as the basket write. |
| **A shared library** *(chosen)* | One definition of each table's mapping and each query, compiled into every service. The cost is that the library's version is a coupling point between services: see Known limitations. |

### Who writes what

The shared library gives every service every DAO, so "one owner per table" would exist only as a
convention. It is instead **enforced** by `architecture-tests` (ArchUnit): `decrementStockForUpdate`
may only be called from the transaction service, `saveAll` on items only from the catalog service,
`replaceRanking` only from the analytics service, `append` on the scan log only from the transaction
service, and so on. Reads are open to everyone — that is what a shared database is for. The "Writes"
column of the table above is that rule set.

### `scan_log`: how analytics hears about scans without being called

In week 3, `DefaultTransactionService.scan()` called `analyticsService.recordScan(sku)` — an in-process
call. Across a process boundary that would be a direct service-to-service RPC, which the assignment
discourages ("unless absolutely necessary"). It is not necessary: the scan is already being written to
the database, so the transaction service appends one row to `scan_log` *in the same database
transaction as the basket update*, and the analytics service tails that table (`ScanLogSource`) and
feeds each row into the week 3 pipeline's scan pipe.

Consequences, both good and bad:

- **A scan that rolls back is never counted.** Week 3's in-memory offer could not un-send it.
- **Analytics can be down without checkout noticing**, and when it comes back it resumes from the log.
  In week 3 a dead analytics pipeline would have been a dead process.
- **Ordering needs care.** Row ids come from a sequence, and sequence order is not commit order: row 41
  can become visible while row 40's transaction is still open, and a reader past 41 would never see 40.
  `ScanLogSource` therefore only reads rows older than a 250 ms *settle delay* and stops at the first
  row that is too new. Checkout transactions last a few milliseconds, so the delay costs nothing
  visible (the ranking only moves every 500 scans) and closes the gap in practice. It is not a proof:
  a scan transaction held open past 250 ms could still be skipped. Covered by `ScanLogSourceTest`.
- **It costs one extra `INSERT` per scan** on the transaction service's hot path (see the results).

### Why the stock decrement stayed with the transaction service

It would be natural to expect the *inventory* service to own the decrement. It does not, deliberately.
Completing a sale must mark the basket `COMPLETED` **and** decrement every stock row atomically, under
the pessimistic row locks that make "two stations both buy the last unit" impossible. With one shared
database that is a single local `@Transactional` method — exactly as it was in weeks 1–3. If the
transaction service instead called the inventory service over RPC, the decrement and the basket update
would be two commits in two processes, and a crash or timeout between them would either oversell or
lose a sale; fixing that needs a distributed transaction or a compensating saga, i.e. a different
architecture (a later week's, in this course).

So the inventory service is, honestly, thin: it reports on stock. The stock-changing code lives in the
transaction service's `StockLedger` (which keeps week 2's `Propagation.MANDATORY` guard so a decrement
outside a transaction fails loudly rather than silently losing its lock). This is the characteristic
shortcoming of service-based architecture with a shared database: the *database* is where the real
boundaries of consistency are, and services are drawn around them, not the other way round.

## Why gRPC (and not brpc)

The professor suggested RPC between the pieces. Two projects have a name that sounds like "g-b-RPC":

- **gRPC** — Google's RPC framework: HTTP/2 transport, Protocol Buffers contracts, first-class Java.
- **brpc** — Baidu's (now Apache) C++ RPC framework. Strong in C++; its Java story is much thinner.

I used **gRPC** (`grpc-java` 1.68 with Netty). Everything else in the repository is Java, and the
gateway→service hop is exactly the strongly-typed, request/response, internal call gRPC is built for.
The `.proto` file is the service contract in the same way the OpenAPI file is the public one, and both
sides are generated from it, so a field cannot silently mean different things to caller and callee.

RPC is used in exactly one place — **gateway → domain service** — because the public contract is fixed
REST/JSON (the load client is not allowed to change) and something has to turn it into calls on
four different processes. No domain service uses it to talk to another.

### Errors across the wire

Business-rule failures keep their week 3 meaning end to end. A service throws a `DomainException`;
`DomainErrorInterceptor` turns it into a gRPC status (`NOT_FOUND`, `FAILED_PRECONDITION`,
`INVALID_ARGUMENT`) plus the contract's error code (`UNKNOWN_SKU`, `TRANSACTION_NOT_OPEN`, …) in a
trailer; the gateway's `GlobalExceptionHandler` maps that to the HTTP status and rebuilds the same
`{"error": …, "message": …}` body. Two outcomes exist now that did not before and have no week 3
equivalent: **503** when a service is down and **504** when it misses its 10 s deadline.

## Architectural Characteristics

| Characteristic | Week 3 (pipeline) | Week 4 (service-based) |
| -------------- | ----------------- | ---------------------- |
| **Deployability** | One jar; any change redeploys everything | Five jars; the analytics pipeline can be redeployed without touching checkout |
| **Fault isolation** | A fault in analytics shares a JVM with checkout | Analytics, catalog and inventory can be down while checkout continues (verified in the results) |
| **Scalability** | The whole application scales as one | Each service scales on its own; checkout is the one that needs it |
| **Modularity** | Package boundaries, enforced by ArchUnit | Process and module boundaries, enforced by Maven (a service cannot import another) and by ArchUnit |
| **Consistency** | One ACID transaction per sale | **Unchanged** — still one ACID transaction per sale, because the database is shared |
| **Performance** | In-process calls | One gRPC hop per request, one extra insert per scan |
| **Simplicity** | One process to run and debug | Six processes (five JVMs + Postgres), a gateway, a schema owner, a library version to keep in step |
| **Data independence** | n/a | **Lost** — the schema is shared, so no service can change its tables alone |

## Load Test Results

Week 4's two reports: `reports/report-20261005-210516.json` (default, 10 stations × 60 s) and
`reports/report-20261005-210745.json` (stress, 100 × 120). Baseline: week 3 re-run in the same session,
`week3-pipeline/reports/report-20261005-210904.json` and `report-20261005-211357.json`, each on a freshly
reset database after a discarded warm-up run.

| | Default: week 3 | Default: week 4 | Stress: week 3 | Stress: week 4 |
| - | - | - | - | - |
| Throughput (tx/sec) | 106.1 | 90.4 | 80.1 | 76.3 |
| START p95 / p99 | 4.7 / 7.4 ms | 6.0 / 11.6 ms | 81 / 134 ms | 84 / 295 ms |
| SCAN p95 / p99 | 10.5 / 14.0 ms | 11.6 / 17.0 ms | 88 / 125 ms | 96 / 148 ms |
| COMPLETE p95 / p99 | 60.9 / 107.9 ms | 65.6 / 107.8 ms | 1.80 / 3.12 s | 1.82 / 3.11 s |
| Errors | 0 | 0 | 0 | 0 |

**What the numbers say.**

- *The extra hop costs about a millisecond.* Mean START rises 2.65 → 3.78 ms and mean SCAN 6.18 → 7.33 ms:
  one gateway→service gRPC call each, plus one `scan_log` insert per scan. At 10 closed-loop stations that
  is a ~15 % throughput drop. The cost is real but small, and on one machine it is a floor — a network
  would add to it.
- *The bottleneck did not move.* Under stress, `COMPLETE_TRANSACTION` p95/p99 is 1.80/3.12 s in week 3 and
  1.82/3.11 s in week 4. That tail is the pessimistic row lock on Zipf-hot SKUs inside PostgreSQL, which
  five processes share exactly as one process did. Services bought deployability and isolation, not
  throughput. Stress throughput being 5 % lower is within the repo's documented run-to-run noise; the START
  p99 gap (134 → 295 ms) is a single-run tail and I would not read an effect into it.
- *Correctness held.* Both runs: 0 lost updates, 0 negative stock; units decremented equal units sold
  (apart from the demand-over-supply flooring of `SKU-000001`, explained in the README). `scan_log` holds
  one row per scan and the analytics pipeline consumed every one (57 709 and 97 041), dropping none.
- *Fault isolation works.* With the analytics, inventory and catalog services killed, checkout still
  completed sales, and the three dead endpoints answered `503 SERVICE_UNAVAILABLE`. Week 3 could not
  lose analytics without losing the process.

## Design Decisions

### Thread pools and the gRPC server

Each service runs its methods on a fixed pool (`GrpcServerLifecycle`) instead of gRPC's default
unbounded cached pool. Methods block on JDBC, so the pool size *is* the service's concurrency limit.
The transaction service gets 200 worker threads and 50 Hikari connections — the 200/50 held constant
since week 1, now sitting where the work is — and the gateway keeps Tomcat's 200. The other services
get 16 threads and 5 connections or fewer. Total connections across services is at most 62, under
PostgreSQL's default `max_connections` of 100.

The gateway opens 4 HTTP/2 channels per service and spreads calls round-robin. One channel multiplexes
many calls but pins them all to one Netty event loop; four is a small, cheap hedge, not a tuned value.

### Why the services have no web server

They set `spring.main.web-application-type=none`: a service's only door is its gRPC port. gRPC's own
threads are daemons, so a non-daemon thread parked on `server.awaitTermination()` keeps the JVM alive.
The gRPC server is the last lifecycle bean to start (so seeding and the pipeline are up before the
first call) and the first to stop.

### Schema ownership

`db/init.sql` is the schema, applied by PostgreSQL on first start; every service runs
`ddl-auto=none`. It reproduces what Hibernate generated in weeks 1–3, deliberately including the
missing index on `transaction_items.tx_id`, so the SQL costs what it cost then and the comparison stays
about architecture. (The index would obviously help; it is a candidate for a later week and would be a
single line in one file — which is the upside of a shared schema.)

### Concurrency & Correctness (checkout)

Unchanged from weeks 1–3: on completion, the SKUs are sorted and each row is taken with
`SELECT … FOR UPDATE` inside the completion's `@Transactional` boundary, so concurrent completions
serialize per SKU in a stable order and cannot deadlock or oversell. The invariant — for every SKU,
`initial_stock − final_stock` equals completed units sold, and stock is never negative — is checked
against the database after every run; the figures are in the results.

### Known limitations

- **The database is a single point of failure and a single point of contention.** Splitting the
  application into five processes did nothing for the one resource that limits checkout, the row
  locks in PostgreSQL. This architecture buys deployability and isolation, not throughput.
- **The shared schema couples the services.** Renaming `items.stock` means changing `shared-db`
  and redeploying every service that links it, in lockstep. The Maven build catches a mismatch at
  compile time, but nothing catches a service still running an old jar against a new schema. This is
  the "distributed monolith" risk, and it is what the next architecture styles in the course address.
- **A service's data access is not isolated from its neighbours' load.** Analytics' queries and
  checkout's locks share one PostgreSQL; a runaway analytics query can slow checkout.
- **The gateway is a single point of failure** and, as it forwards synchronously, adds its own
  latency to every request. It is stateless, so running several behind a load balancer would work;
  that was not done.
- **No service discovery or health-based routing.** Addresses are in `application.properties`.
- **`scan_log` grows without bound.** Nothing prunes it; analytics only ever reads the newest
  window's worth. A retention job is the obvious fix.
- **Analytics' window numbers are per-process.** `windowStart`/`windowEnd` count scans since the
  analytics service began reading (after replaying up to one window on restart), as they counted
  since JVM start in week 3 — they are not the `scan_log` ids.
- **The settle-delay is a heuristic**, described above.
- **No TLS, authentication or retries** between gateway and services: this is a single-machine
  coursework system, and gRPC calls fail fast to a 503/504 rather than being retried.
