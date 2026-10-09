# Week 4 — Service-Based Architecture

The same self-checkout contract (`spec/self-checkout-openapi.yaml`), driven by the same unmodified
`load-client/`. The application is now cut along **business functions** into separately deployable
**domain services** that share **one centralized database** and do not call each other. A gateway
keeps the public REST contract on port 8080; behind it, calls travel over **gRPC**.

## Services

| # | Service | Port | What it does |
| - | ------- | ---- | ------------ |
| 1 | **catalog-service** | 9101 | Serves the item catalog (`GET /items`); seeds it on first run |
| 2 | **transaction-service** | 9102 | Checkout: start, scan, complete, status — including the row-locked stock decrement |
| 3 | **inventory-service** | 9103 | Stock reporting (`GET /inventory/low-stock`) |
| 4 | **analytics-service** | 9104 | Windowed popular items (`GET /analytics/popular-items`) — week 3's pipeline |

Plus the **gateway** (port 8080), which is the REST/JSON front door and holds no database
connection, and one **PostgreSQL** (`supermarket-pg-week4`, host port 5435).

How they fit together, why the stock decrement lives where it does, why gRPC, and what this costs:
[ARCHITECTURE.md](ARCHITECTURE.md).

## Layout

```
week4-services/
├── shared-db/            shared database access library: domain kernel, entities, DAOs (linked into every service)
├── contracts/            supermarket.proto + generated gRPC stubs + wire error mapping
├── service-support/      gRPC server lifecycle and domain-error interceptor shared by the services
├── catalog-service/      ┐
├── transaction-service/  │ the four domain services — one Spring Boot jar each, gRPC only
├── inventory-service/    │
├── analytics-service/    ┘
├── gateway/              REST → gRPC
├── architecture-tests/   ArchUnit rules over the whole system
├── db/init.sql           the one schema
├── docker-compose.yml    PostgreSQL
├── run-local.sh / stop-local.sh
└── reports/              load-client JSON reports
```

## Running it

Requires JDK 17+ and Docker. (The first build downloads the `protoc` compiler from Maven Central; no
local install is needed.)

```bash
cd week4-services
docker compose up -d                  # PostgreSQL on 5435; db/init.sql creates the schema
./mvnw -DskipTests package            # builds every module; each service gets target/<name>-1.0.0-exec.jar
./run-local.sh                        # five JVMs, logs in logs/<service>.log
```

`run-local.sh` starts the catalog service first (it seeds 2000 items × 10 000 units on an empty
database), then inventory, transaction, analytics and the gateway, and waits for the
`Seeded catalog: 2000 items` line. Stop everything with `./stop-local.sh`. Run one week's server at a
time: the gateway takes port 8080 like every week's app.

Each jar is also runnable on its own (`java -jar transaction-service/target/transaction-service-1.0.0-exec.jar`)
— that is the point. Ports and the database URL are in each module's `application.properties`.

```bash
# in another shell
cd ../load-client && ./build.sh
./run.sh --baseUrl=http://localhost:8080 --reportDir=../week4-services/reports                              # default
./run.sh --baseUrl=http://localhost:8080 --stations=100 --duration=120 --reportDir=../week4-services/reports # stress
```

`docker compose down -v` resets stock for a fresh run (and re-applies `db/init.sql`); restart the
services afterwards so the catalog is re-seeded.

## Tests

`./mvnw test` runs 22 tests:

- **10 architecture rules** (`architecture-tests`):
  1. no service depends on another service's code;
  2. no domain service holds an RPC client — only the gateway makes calls;
  3. the gateway has no database, JPA or domain-kernel dependency;
  4. only the gateway knows HTTP;
  5. entities, Spring Data and JPA stay inside the shared library;
  6–10. **table ownership** — only the transaction service may decrement stock, write baskets, or append to
     `scan_log`; only the catalog service may seed `items`; only the analytics service may rewrite
     `popular_items`.

  The ownership rules were checked to fail: a throw-away class in the catalog service that called
  `decrementStockForUpdate` trips the stock-decrement rule.
- **3 pipeline rules** and **5 pipeline tests** carried over from week 3 (analytics-service).
- **4 `ScanLogSource` tests**: events are fed in id order and never read twice; events younger than the
  settle delay are held back; reading stops at the first unsettled event so order is preserved; a restart
  resumes one window before the end of the log.

## Results (this machine)

Week 4's two submission reports are in `reports/`:

- `report-20261005-210516.json` — default (10 stations, 60 s)
- `report-20261005-210745.json` — stress (100 stations, 120 s, i.e. `--stations=100 --duration=120`)

Week 3 was **re-run in the same session at both loads** as the like-for-like baseline
(`week3-pipeline/reports/report-20261005-210904.json` default, `report-20261005-211357.json` stress).
Same laptop, on battery, Docker warmed up first (a discarded 40 s warm-up run), each run on a freshly
reset database and freshly started JVMs.

### Default mode — 10 stations, 60 s

| Metric | Week 3 | Week 4 |
| ------ | ------ | ------ |
| Throughput | **106.1 tx/sec** | 90.4 tx/sec |
| Items/sec | **1 109.5** | 960.0 |
| START p95 / p99 | **4.7 / 7.4 ms** | 6.0 / 11.6 ms |
| SCAN p95 / p99 | **10.5 / 14.0 ms** | 11.6 / 17.0 ms |
| COMPLETE p95 / p99 | 60.9 / 107.9 ms | 65.6 / 107.8 ms |
| Errors | 0 | 0 |

### Stress mode — 100 stations, 120 s

| Metric | Week 3 | Week 4 |
| ------ | ------ | ------ |
| Throughput | **80.1 tx/sec** | 76.3 tx/sec |
| Items/sec | **839.3** | 802.6 |
| START p95 / p99 | **81 / 134 ms** | 84 / 295 ms |
| SCAN p95 / p99 | **88 / 125 ms** | 96 / 148 ms |
| COMPLETE p95 / p99 | 1.80 / 3.12 s | 1.82 / 3.11 s |
| Errors | 0 | 0 |

**The honest reading: week 4 is a little slower, and the cost is the extra hop, not a change in the
bottleneck.** At low load every request pays for one gateway→service gRPC call, and every scan pays for
one extra `INSERT` into `scan_log`: mean START and SCAN latency rise by about 1.1 ms each (3.78 vs 2.65 ms,
7.33 vs 6.18 ms), which at 10 closed-loop stations is a ~15 % throughput drop. Under stress the
`COMPLETE_TRANSACTION` tail — 1.8 s p95, 3.1 s p99 — is **the same in both weeks to within 1 %**, because it
is the pessimistic row lock on Zipf-hot SKUs in PostgreSQL, and splitting the application into services
does nothing to it. Stress throughput is 5 % lower, which is inside the run-to-run noise this repo has
documented (~25 % between cold and warm runs of one jar); the START p99 (295 vs 134 ms) is the one number
that looks like a real difference and is a single-run tail, so I would not lean on it. This is a
single-machine run: with the services on separate hosts the network would add to every hop.

The popular-items ranking is the same as in every earlier week (`SKU-000001`, `-02`, `-03`, `-04` on top).

### Correctness invariant

Checked against the database after each run:

- **Default:** 0 lost updates, 0 negative-stock rows. 57 709 units decremented = 57 709 units in completed
  transactions = the client's reported scan count.
- **Stress:** 0 lost updates, 0 negative-stock rows. 97 041 units sold against 95 154 decremented: the
  1 887-unit gap is demand for `SKU-000001` beyond its 10 000-unit supply, which the decrement correctly floors
  at 0 (the Zipf sampler sends ~12 % of scans to rank 1) — the same legitimate demand-over-supply as weeks 1–3,
  not a concurrency defect.
- **`scan_log` accounting:** the table holds exactly one row per scanned unit (57 709 and 97 041), and on
  shutdown the analytics pipeline's Window stage had handled exactly that many, with **0 scans dropped and
  0 snapshots superseded** in both runs.

```bash
docker exec -i supermarket-pg-week4 psql -U supermarket -d supermarket <<'SQL'
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

### Fault isolation (checked by hand)

With the analytics, inventory and catalog services killed, start/scan/complete kept working (200s, receipt
correct), while `GET /items`, `/inventory/low-stock` and `/analytics/popular-items` returned
`503 SERVICE_UNAVAILABLE` — which week 3, being one process, could not do. Restarting analytics brought its
endpoint back; it resumes tailing `scan_log` (`ScanLogSourceTest` covers the replay logic).

See [ARCHITECTURE.md](ARCHITECTURE.md) for characteristics, trade-offs and the evidence behind them.
