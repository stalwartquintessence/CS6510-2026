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
├── experiments/hop-cost/ the per-call probe (HopProbe.java) and the script that measured it
└── reports/              load-client JSON reports; fault-injection/ for the fault runs; experiments/ for the two experiments
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

`./mvnw test` runs 40 tests:

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
- **6 ledger tests** (`StockLedgerTest`, inventory-service): deduct is idempotent; stock is floored and
  restore returns only what was taken; restore is idempotent; a restore that overtakes its deduct
  leaves a tombstone (and the next attempt's sale id is not blocked); rows are locked in SKU order;
  the lock-order experiment's descending order is used by deduct and restore alike.
- **12 in analytics-service**: week 3's 3 pipeline rules and 5 pipeline tests, and week 4's 4 scan-log
  tests ported to the scan feed (`ScanFeedSourceTest`).

## Results (this machine)

Run on 2026-10-24 on one M1 Pro laptop (8 cores) on AC power. Docker was warmed up with a discarded run,
and every run started on freshly reset databases with freshly started JVMs. **Week 4 was re-run in the same
session**, interleaved with week 5 (week 4 first in round 1, week 5 first in round 2), so the comparison is
like-for-like. Its reports are in `../week4-services/reports/`.

| Run | Week 4 | Week 5 (`reports/`) |
| --- | ------ | ------------------- |
| Default, round 1 | `report-20261024-234006.json` | `report-20261024-234135.json` |
| Stress, round 1 | `report-20261024-234405.json` | `report-20261024-234635.json` |
| Default, round 2 | `report-20261024-234932.json` | `report-20261024-234804.json` |
| Stress, round 2 | `report-20261024-235432.json` | `report-20261024-235201.json` |

### Default mode — 10 stations, 60 s

| | Week 4 r1 | Week 4 r2 | Week 5 r1 | Week 5 r2 |
| - | - | - | - | - |
| Transactions | 5 184 (86.2/s) | 5 523 (91.9/s) | 4 170 (69.4/s) | 4 930 (82.0/s) |
| START mean / p99 | 4.0 / 12.2 ms | 3.5 / 8.9 ms | 4.9 / 18.3 ms | 3.9 / 12.2 ms |
| SCAN mean / p99 | 7.8 / 19.5 ms | 7.3 / 16.2 ms | 8.8 / 28.5 ms | 7.8 / 19.2 ms |
| COMPLETE mean / p99 | 30.6 / 112 ms | 28.1 / 94 ms | 47.6 / 218 ms | 35.9 / 123 ms |
| Errors | 0 | 0 | 0 | 0 |

### Stress mode — 100 stations, 120 s

| | Week 4 r1 | Week 4 r2 | Week 5 r1 | Week 5 r2 |
| - | - | - | - | - |
| Transactions | 9 533 (78.8/s) | 9 263 (76.6/s) | 9 196 (76.2/s) | 9 325 (77.3/s) |
| START mean / p99 | 58 / 270 ms | 59 / 289 ms | 9.2 / 195 ms | 8.9 / 204 ms |
| SCAN mean / p99 | 63 / 129 ms | 65 / 134 ms | 17.8 / 109 ms | 17.9 / 106 ms |
| COMPLETE mean | 554 ms | 569 ms | 1 110 ms | 1 093 ms |
| COMPLETE p95 / p99 | 1.78 / 2.99 s | 1.89 / 3.26 s | 2.72 / 4.35 s | 2.65 / 4.13 s |
| Errors | 0 | 0 | 3 completes (timed out; compensated) | 0 |

**What they say.**

- **At the default load, week 5 is slower, by roughly 10–20 %**: 4 550 transactions against 5 354, averaged
  over the two rounds (−15 %). The cost is in `complete`, which is now three local transactions in two
  databases plus a service-to-service call (freeze, deduct, mark completed) where week 4 had one transaction;
  its mean rises from 28–31 ms to 36–48 ms. START and SCAN are unchanged within noise. Week 5's two rounds
  differ by 18 %, so the size of the gap is uncertain, but both rounds point the same way.
- **Under stress, throughput is the same but the waiting moved.** 9 260 against 9 398 transactions on
  average (−1.5 %, well inside the noise). START and SCAN got 3–6× faster and `complete` twice as slow. In
  both weeks the cap is the row lock on `SKU-000001`, which is in about two in three baskets; splitting the
  database did not touch it. What changed is where the other stations wait:
  - In week 4, a `complete` waiting for that lock holds one of the transaction service's 50 database
    connections, which starts and scans also need. Fewer stations wait at the lock and more queue in the
    scan path (about 52 scanning at once).
  - In week 5 the wait holds an inventory connection instead. About 85 stations park in `complete`, and the
    15 left scanning go fast.

  Little's law accounts for both exactly, in [ARCHITECTURE.md](ARCHITECTURE.md#load-test-results).
- **The 3 errors** (3 of 18 524 stress completes) are the saga's deadline at work. Those three stock steps
  waited longer than 9 s for the hot row lock. The saga gave up, compensated (the stock was put back), and the
  client's own 10 s timeout fired before the 503 arrived. Week 4 has no such deadline, so its longest waits
  (7.7 s) were just slow.
- The popular-items ranking has `SKU-000001` and `-000002` on top in every run, as in every earlier week; third
  is `-000003` or `-000004`, which a 1 000-scan window cannot reliably separate.

### Correctness invariant

`./verify-invariant.sh` joins transaction-db and inventory-db. It **held after every run**: the four above, the
experiments and all four fault-injection runs. That means 0 lost updates and 0 negative stock; every completed
transaction has exactly one applied sale in the inventory ledger and no applied sale lacks one; inventory was
asked for exactly what was sold; and no saga was left in flight.

- Default runs: units decremented = units sold (43 533 and 51 775).
- Stress runs: 96 595 and 98 197 units sold, of which 1 867 and 2 056 were demand for `SKU-000001` beyond its
  10 000 units. The decrement floors that at 0 and the ledger records it (applied < requested), as in every
  week.
- In the run with 3 failed completes, transaction-db counts 96 595 units sold against the client's 96 644
  scanned. The 49 missing units are those three compensated baskets.
- Week 4's same-session runs passed its own check (0 lost updates, 0 negative stock).

### Fault injection

`./fault-injection.sh <service>`: 10 stations × 60 s, with the victim killed (`kill -9`) at +20 s, restarted 15 s
later, and the invariant checked once the load ends. Reports are in `reports/fault-injection/`.

| Killed | While it was down | Back after restart | Afterwards |
| ------ | ----------------- | ------------------ | ---------- |
| inventory | `complete` failed fast (503, mean 44 ms; 1 837 of them); start and scan unaffected | 0.1 s | the recovery job compensated the stuck sagas within ~40 s; invariant holds |
| transaction | checkout down (it *is* checkout): 247 742 failed starts (the client retries at once), 6 scans, 3 completes | 0.2 s | recovery compensated the 3 sagas the crash cut off; invariant holds |
| analytics | **0 errors** | 0.2 s | invariant holds |
| catalog | **0 errors** (the item cache carries checkout) | 0.2 s | invariant holds |

"Back after restart" is the time from the restarted service finishing startup to the first successful request
through the gateway that needs it. Before the reconnect watchdog was added it was 6.6 s
([ARCHITECTURE.md](ARCHITECTURE.md#fault-injection)).

### Experiments

These answer two questions from the week 4 review. They are kept apart from the comparison above (reports in
`reports/experiments/`), and the defaults that comparison used are unchanged.

- **Lock order** (`supermarket.inventory.lock-order=SKU_DESCENDING`, which takes the hottest row lock last).
  Under stress, four runs alternated with the default order: +14 % throughput (10 286 against 9 029
  transactions on average), `complete` mean 1.13 s → 0.31 s, p99 4.5 s → 2.0 s, and no timeouts. No change at
  the default load. To re-run it, start the system with
  `JAVA_OPTS=-Dsupermarket.inventory.lock-order=SKU_DESCENDING ./run-local.sh`.
- **What one call costs.** Timed alone, a bare gRPC call between two JVMs on this laptop takes 0.08 ms back
  to back and 0.3–0.4 ms when calls are spaced out, idle or under load. A real catalog call, including its
  database lookup, takes 0.5–2 ms. Week 4's "about 2 ms per call" was not a per-call measurement: it was the
  rise in request latency from week 3 to week 4, on a machine whose CPU is about 95 % busy even at 10
  stations, so every bit of work added per request also waits for the CPU. Most of that CPU goes to
  transaction-db's sequential scans of `transaction_items`, which has no index on `tx_id` (kept deliberately
  since week 1). On separate hosts most of the contention would go, and each hop would add a network round
  trip. The probe and the script that ran it are in `experiments/hop-cost/` (`./hop-cost.sh`, about 12
  minutes).

Details, trade-offs and the evidence behind them: [ARCHITECTURE.md](ARCHITECTURE.md).
