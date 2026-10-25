# Week 5 — Microservices

The same self-checkout contract (`spec/self-checkout-openapi.yaml`), driven by the same unmodified
`load-client/`. Week 4's four services now each **own their data store** — four separate PostgreSQL
servers, and no shared library or table. The one request that has to change two of those databases,
completing a sale (the basket in the transaction service, the stock in the inventory service), is
an **orchestrated saga** run by the transaction service, with compensation and crash recovery. An
API gateway keeps the public REST contract on port 8080; behind it, everything is **gRPC**. There is
no message broker.

## Services

| # | Service | Port | Own database | What it does |
| - | ------- | ---- | ------------ | ------------ |
| 1 | **catalog-service** | 9101 | `catalog-db` :5436 | Serves the item catalog (`GET /items`) and single items to the other services; seeds it on first run |
| 2 | **transaction-service** | 9102 | `transaction-db` :5437 | Checkout: start, scan, complete, status. **Orchestrates the checkout saga**; publishes scans through its outbox (`ScanFeed`) |
| 3 | **inventory-service** | 9103 | `inventory-db` :5438 | Owns stock: low-stock reports (`GET /inventory/low-stock`) and the saga's idempotent `DeductStock` / `RestoreStock` |
| 4 | **analytics-service** | 9104 | `analytics-db` :5439 | Windowed popular items (`GET /analytics/popular-items`) — week 3's pipeline, fed by the scan feed |

Plus the **API gateway** (port 8080): the REST/JSON front door, no database.

How the data is split, how the saga works and fails, why orchestration and no broker, and what it
costs: [ARCHITECTURE.md](ARCHITECTURE.md).

## Layout

```
week5-microservices/
├── contracts/            supermarket.proto: the 4 public services + 2 internal ones (StockService, ScanFeed)
├── service-support/      the service chassis: gRPC server lifecycle, client stub pools, the error vocabulary
├── catalog-service/      ┐
├── transaction-service/  │ the four microservices — one Spring Boot jar each, gRPC only,
├── inventory-service/    │ each with a private persistence package and its own database
├── analytics-service/    ┘
├── gateway/              REST → gRPC
├── architecture-tests/   ArchUnit rules over the whole system + the database-per-service check
├── db/                   one schema per database: catalog.sql, transaction.sql, inventory.sql, analytics.sql
├── docker-compose.yml    four PostgreSQL servers
├── run-local.sh / stop-local.sh      all services, or one (independent deployment)
├── verify-invariant.sh   the correctness invariant, checked across the two databases a sale spans
├── fault-injection.sh    load + kill -9 one service mid-run + restart + recovery + invariant
└── reports/              load-client JSON reports; reports/fault-injection/ for the fault runs
```

## Running it

Requires JDK 17+ and Docker. (The first build downloads `protoc` from Maven Central.)

```bash
cd week5-microservices
docker compose up -d --wait           # catalog-db, transaction-db, inventory-db, analytics-db (5436-5439)
./mvnw -DskipTests package            # each service gets target/<name>-1.0.0-exec.jar
./run-local.sh                        # five JVMs, logs in logs/<service>.log
```

`run-local.sh` starts the catalog first (it seeds 2000 items), then inventory (it asks the catalog
for the items and seeds 10 000 units of each), then transaction, analytics and the gateway, waiting
for each seed to finish. `./run-local.sh <service>` (re)starts just one service and `./stop-local.sh
<service>` stops just one — redeploying one microservice without touching the others. Run one week's
server at a time: the gateway takes port 8080 like every week's app, and `run-local.sh` refuses to
start a service whose port is already taken.

```bash
# in another shell
cd ../load-client && ./build.sh
./run.sh --baseUrl=http://localhost:8080 --reportDir=../week5-microservices/reports                              # default
./run.sh --baseUrl=http://localhost:8080 --stations=100 --duration=120 --reportDir=../week5-microservices/reports # stress
cd ../week5-microservices && ./verify-invariant.sh
```

For a fresh run: `./stop-local.sh && docker compose down -v && docker compose up -d --wait &&
./run-local.sh` (an empty database is what triggers the seeds).

## Tests

`./mvnw test` runs 39 tests:

- **13 in `architecture-tests`.** 11 ArchUnit rules:
  1. no service depends on another service's code;
  2–6. **the call graph**, one rule per service: which service's stubs it may hold. The catalog calls
     nobody; inventory calls only the catalog; transaction calls the catalog and `StockService`;
     analytics calls `ScanFeed` and the catalog; the gateway calls only the four public services,
     never the internal ones;
  7. the gateway has no database;
  8. JPA and Spring Data stay inside each service's own `persistence` package;
  9. every `@Entity` lives in exactly one service's `persistence` package;
  10. the shared modules (`contracts`, `service-support`) touch no database;
  11. only the gateway knows HTTP.

  Plus 2 configuration checks (`DatabasePerServiceTest`): the four services are configured with four
  different database servers, databases and roles, and the gateway with none. The call-graph and
  persistence rules were checked to fail: a throw-away catalog class holding a `StockService` stub and
  an `EntityManager` trips both.
- **9 saga tests** (`CheckoutSagaTest`, transaction-service), against an in-memory inventory that can
  be unreachable, lose its reply after committing, or stall mid-call: the happy path; an outage
  reopens the basket and the next attempt succeeds; a lost reply is compensated; a failed
  compensation is finished by recovery; a saga abandoned by a crash is recovered only once it is
  stuck; a second `complete` during a saga is refused and inventory is called once; recovery
  overtaking a slow request leaves a tombstone that refuses the late deduct; recovery claiming a
  saga after its deduct stops the request from completing it (`CHECKOUT_ABORTED`); an empty basket
  never reaches inventory.
- **5 ledger tests** (`StockLedgerTest`, inventory-service): deduct is idempotent; stock is floored and
  restore returns only what was taken; restore is idempotent; a restore that overtakes its deduct
  leaves a tombstone (and the next attempt's sale id is not blocked); rows are locked in SKU order.
- **12 in analytics-service**: week 3's 3 pipeline rules and 5 pipeline tests, and week 4's 4 scan-log
  tests ported to the scan feed (`ScanFeedSourceTest`).

## Results

_Results write-up in progress: the raw load-client reports are in `reports/` (same-session week 4 baselines in `../week4-services/reports/`) and `reports/fault-injection/`._
