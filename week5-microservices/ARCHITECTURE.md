# Self-Checkout Microservices Architecture Analysis

> All figures are from one M1 Pro laptop (8 cores) on AC power, every service and database on the same
> machine, measured on 2026-10-24/25. Its CPU is about 95 % busy at both the default and the stress
> load, which shapes every latency below (see *"2 ms per call is high?"*).

## The services

| Service | Port | Owns (its own PostgreSQL) | Serves | Calls |
| ------- | ---- | ------------------------- | ------ | ----- |
| **catalog-service** | 9101 | `catalog-db` :5436 — `items` (sku, name, price) | `CatalogService.ListItems / GetItem` | nobody |
| **transaction-service** | 9102 | `transaction-db` :5437 — `transactions`, `transaction_items`, `scan_events` (outbox) | `TransactionService.Start / Scan / Complete / GetStatus`; internal `ScanFeed` | catalog (cache misses), inventory's `StockService` (the saga) |
| **inventory-service** | 9103 | `inventory-db` :5438 — `stock_items`, `sales`, `sale_lines` (saga ledger) | `InventoryService.GetLowStock`; internal `StockService.DeductStock / RestoreStock` | catalog (once, to seed) |
| **analytics-service** | 9104 | `analytics-db` :5439 — `popular_items` | `AnalyticsService.GetPopularItems` | transaction's `ScanFeed`, catalog (names) |
| API gateway | 8080 | — (no database) | the REST contract | the four public services |

Five deployables (one Spring Boot jar and JVM each) and four databases (one PostgreSQL server per
service, each with its own role and volume). No service has the address or credentials of another
service's database; the only way to another service's data is its RPCs.

```
                         load client ──HTTP/JSON──▶ API gateway :8080
                         (unchanged)                (REST → gRPC, no database)
                                                      │ public RPCs only
               ┌────────────────────┬─────────────────┼──────────────────┐
               ▼                    ▼                 ▼                  ▼
        ┌─────────────┐   ┌──────────────────┐   ┌──────────────┐   ┌──────────────┐
        │ catalog     │◀──│ transaction      │──▶│ inventory    │   │ analytics    │
        │ :9101       │   │ :9102            │   │ :9103        │   │ :9104        │
        │             │   │ ORCHESTRATOR     │   │              │   │              │
        │             │◀──┼──────────────────┼───│ (seed, once) │   │              │
        │             │◀──┼──────────────────┼───┼──────────────┼───│ (names)      │
        │             │   │ ScanFeed ◀───────┼───┼──────────────┼───│ (scans)      │
        └──────┬──────┘   └────────┬─────────┘   └──────┬───────┘   └──────┬───────┘
               ▼                   ▼                    ▼                  ▼
          catalog-db         transaction-db        inventory-db       analytics-db
            :5436                :5437                 :5438              :5439
```

The call graph is acyclic, and it is enforced, not just drawn: holding a service's generated stub
is the only way to call it, and `MicroservicesArchitectureTest` fixes which service may hold which
stub. The catalog is the leaf everyone may depend on; nothing but the gateway calls analytics; the
two internal services (`StockService`, `ScanFeed`) have exactly one client each, and the gateway is
not it.

## What changed from week 4

Week 4 was *service-based*: the same four services, but over **one shared database**, linked into
every service as a `shared-db` library. That shared database was the whole of their coupling, and
it was also what kept `complete` simple: the basket update and the stock decrement were one local
ACID transaction. Week 5 gives every service its own data store, and everything else follows from
that:

1. **`shared-db` is gone.** Each service has a private `persistence` package (entities, Spring
   Data repositories, DAO ports) and its own schema file (`db/<service>.sql`). What is still shared
   is the wire contract (`contracts`, the `.proto`) and the service chassis (`service-support`: gRPC
   server lifecycle, client stub pools, the error vocabulary) — neither knows about persistence,
   and an ArchUnit rule keeps it that way.
2. **`complete` is a saga.** The basket is in the transaction service's database and the stock is
   in the inventory service's, so the sale can no longer be one transaction. The transaction service
   orchestrates it: freeze the basket, ask inventory to deduct, mark the basket completed — and
   compensate if the middle step fails (below).
3. **Services call each other**, which week 4 forbade. Each edge exists because a piece of data
   moved: stock moved to inventory, so checkout must call inventory; the catalog moved behind the
   catalog service, so checkout, inventory and analytics must ask for it.
4. **`scan_log` became an outbox.** It is now `scan_events`, private to the transaction service and
   written in the scan's own transaction exactly as before; analytics reads it through the
   `ScanFeed` RPC instead of with SQL.
5. **The catalog is cached where it is used.** Checkout no longer reads `items` on every scan; it
   holds a read-through copy of the catalog (warmed at startup), and inventory copies item names
   into its stock rows when it seeds.
6. **The stock decrement moved to the inventory service** — where week 4's analysis said it
   belonged but could not be, as long as atomicity with the basket required a shared database.

The gateway's routes, DTOs and error mapping did not change; it gained a reconnect watchdog after
fault injection showed it was needed (see *Fault injection*).

## Data ownership

| Data | Owner | How anyone else gets it |
| ---- | ----- | ----------------------- |
| Items (name, price) | catalog | `ListItems`/`GetItem`. Checkout keeps a read-through cache, warmed with the whole catalog at startup; analytics' Enrich stage caches names; inventory copies names into `stock_items` at seeding |
| Stock | inventory | Only `DeductStock`/`RestoreStock` (the saga) change it; `GetLowStock` reports it |
| Baskets, receipts | transaction | `Start/Scan/Complete/GetStatus` |
| Scans | transaction (`scan_events` outbox) | `ScanFeed.ReadScans` — the consumer keeps its own cursor |
| Popular-items ranking | analytics | `GetPopularItems` |

Copying catalog data into three other services is safe here for one reason, worth stating because
it is what makes the caches *exact* rather than *approximately right*: nothing in the contract
changes an item after it is seeded. If prices could change, each copy would need invalidating —
the catalog would have to publish changes as events — and the caches would become eventually
consistent. That is the general price of a database per service: whatever two services both need,
one owns and the other copies, and the copy is only as fresh as the mechanism that refreshes it.

## The checkout saga

### Why a saga, and why orchestrated

`POST /transactions/{id}/complete` must mark the basket COMPLETED (transaction-db) **and**
decrement every SKU's stock (inventory-db). With two databases owned by two services there are three
ways to make that atomic:

| Option | Why not / why |
| ------ | ------------- |
| **Distributed transaction (2PC/XA)** | Both databases must be up and holding locks for the whole protocol, so each service's availability becomes the other's; it is exactly the coupling a database per service is meant to remove, and the assignment asks for coordination between services, not between databases. |
| **Choreography** — the transaction service commits the sale plus a `SaleCompleted` event; inventory reacts | `complete` would return 200 before stock was decremented, so the contract's synchronous "complete, take payment, decrement stock" would become eventually consistent; it needs a broker (or a feed) on the critical path; and the steps of one sale end up spread across services' event handlers. It is the event-driven week's design. |
| **Orchestration** *(chosen)* | One component — the transaction service, which owns the request — runs the steps in order and decides what happens on failure. Every state the sale can be in is a column value in one table, so it can be inspected, tested with fakes and recovered after a crash. A 200 still means the stock has been decremented. |

### The steps

```
OPEN ──(1) freeze──▶ COMPLETING ──(2) DeductStock ok──▶ (3) COMPLETED ──▶ 200 + receipt
                          │
                          └── (2) failed / timed out ──▶ COMPENSATING ──RestoreStock ok──▶ OPEN ──▶ 503
                                                              ▲
                                     recovery job (every 5 s) ┘ finishes sagas stuck > 30 s
```

| Step | Where | Local transaction |
| ---- | ----- | ----------------- |
| 1. **Freeze** | transaction-db | Lock the basket row, check it is open and not empty, set `COMPLETING`, increment `saga_attempt`. A concurrent scan or second `complete` waits on the lock, then sees it is not open (409). |
| 2. **DeductStock**(sale id = `txId/attempt`, lines) | inventory-db | Insert the sale id into `sales` (the idempotency key), then for each SKU in sorted order `SELECT … FOR UPDATE`, take `min(requested, stock)`, and record requested and applied in `sale_lines`. |
| 3. **Complete** | transaction-db | `UPDATE … SET status = 'COMPLETED' WHERE id = ? AND saga_attempt = ? AND status = 'COMPLETING'`. Only then is the receipt returned. |
| Compensation: **RestoreStock**(sale id) | inventory-db | Put back exactly the *applied* quantities (stock is floored at zero, so applied can be less than requested) and mark the sale `REVERTED`. If the deduct never arrived, leave a `REVERTED` tombstone so a late one is refused. |

Each step commits before the next begins, so **no database connection or lock is ever held across
a network call** — in particular the hot stock-row locks are held only for the length of
inventory's own short transaction, never while waiting on the transaction service.

### What makes it safe

- **Idempotency by sale id.** The orchestrator cannot tell "inventory never got the call" from
  "inventory committed and the reply was lost" — both look like a timeout. So both inventory steps
  are idempotent: the `sales` primary key is claimed with `INSERT … ON CONFLICT DO NOTHING`, and
  whichever of deduct and restore arrives first decides; a repeat returns the stored result.
- **One sale id per attempt.** A basket whose completion failed is reopened, and the customer may
  try again. That retry is a new attempt with a new sale id, so it cannot collide with the
  tombstone the failed attempt may have left.
- **Conditional transitions.** Every state change is an `UPDATE … WHERE status = ? AND
  saga_attempt = ?`. Whoever's update matches owns the next step; the request thread and the
  recovery job can never both finish the same saga.
- **The basket is a semantic lock.** While a saga is in flight the basket is neither OPEN nor
  COMPLETED; scans and a second `complete` are refused with 409. (Clients see it as OPEN on
  `GET /transactions/{id}`, because the contract's enum has no word for it.)
- **Compensate, don't retry forward.** The contract is synchronous: when the stock step fails, the
  client is being told the sale did not happen. Undoing the step makes both databases agree with
  what the client was told — a 503 always means "nothing was sold", a 200 always means "stock was
  decremented".

### Every way it can fail

| What fails | What happens | Client sees | End state (both databases) |
| ---------- | ------------ | ----------- | -------------------------- |
| Inventory down when `DeductStock` is sent | Call fails fast (`UNAVAILABLE`) → claim `COMPENSATING` → `RestoreStock` leaves a tombstone → `OPEN` | 503 `INVENTORY_UNAVAILABLE` | Basket open, nothing taken |
| `DeductStock` committed but the reply was lost (timeout, crash) | Same path; `RestoreStock` puts back what was applied | 503 (or 504 if the gateway's 10 s ran out first) | Basket open, stock restored |
| …and `RestoreStock` also fails | Saga left `COMPENSATING`; the recovery job retries it once it is 30 s old | 503 | Same, ≥ 30 s later |
| Orchestrator crashes after freezing (before or after the deduct) | Saga left `COMPLETING`; after restart the recovery job claims and compensates it | Connection error | Basket open, stock restored |
| `DeductStock` arrives after its `RestoreStock` | Tombstone: refused, nothing applied | — | Nothing taken |
| Recovery claims a saga whose request is still running | The request's step 3 finds the status changed and fails | 503 `CHECKOUT_ABORTED` | Basket open, stock restored |
| Second `complete`, or a scan, during a saga | Waits on the basket row lock, then fails the open check | 409 | One sale |
| Empty basket | Refused in step 1, before inventory is called | 409 `EMPTY_BASKET` | Unchanged |

Every row of that table has a unit test in `CheckoutSagaTest` (against an in-memory inventory that
can be told to be unreachable, to lose its reply, or to stall mid-call) or `StockLedgerTest`, and the
crash, outage and timeout rows were also produced for real under load (see *Fault injection* and
*Load test results*).

### Deadlines

The gateway gives every call 10 s (unchanged). The stock step gets 9 s, so a stuck deduct fails
inside the gateway's deadline. Compensation runs in a fresh gRPC context with its own 5 s
deadline — not in the incoming call's, which is usually the one that just expired. The recovery job
only touches sagas older than 30 s, well past any request still in flight.

### The concurrency invariant, across a network

The README's warning is that oversell "gets much easier to get wrong once inventory becomes its own
service and the check-then-decrement happens across a network call". The design answer is that the
check and the decrement never cross the network: nothing outside the inventory service ever reads
stock. `DeductStock` checks and decrements inside one inventory transaction, under the same
SKU-ordered `SELECT … FOR UPDATE` as weeks 1–4, so two stations still cannot both take the last
unit, and two sales cannot deadlock. What *does* cross the network is the decision "this sale
happened", and that is what the saga makes atomic.

`verify-invariant.sh` therefore checks more than weeks 1–4 could, joining the two databases' answers:

- for every SKU, units decremented equal units sold (the weeks 1–4 check), and equal the units the
  ledger says were taken — exactly, including the floored SKU;
- no stock below zero;
- every COMPLETED transaction has exactly one applied sale in the inventory ledger, and no applied
  sale lacks a COMPLETED transaction;
- inventory was asked for exactly what was sold;
- no saga is left in flight.

### What the saga gives up

A saga is atomic (eventually) and consistent and durable, but **not isolated**. Between steps 2 and
3, and during a compensation, the stock has been taken for a sale that may yet be undone, and a
`GET /inventory/low-stock` in that window sees it. The basket's frozen state stops anything from
acting on the sale itself, but nothing stops a reader from seeing the intermediate stock. For a
low-stock report a few milliseconds of that is harmless; for something that, say, refused sales on
low stock, it would need a reservation model (deduct as a *pending* hold, confirm in step 3).

## Scans to analytics: an outbox and a feed, no broker

The analytics pipeline needs every scan, in order, exactly once per window, and checkout must not
wait for it or fail because of it. Week 4 did this through the shared `scan_log` table. With
private databases the same table survives as the transaction service's **transactional outbox**,
`scan_events`, appended in the scan's own transaction (so a rolled-back scan is never counted). It
is published through the `ScanFeed` RPC: `ReadScans(afterId, limit)` and `LatestScanId()`.

Analytics pages through it with **its own cursor**, exactly as week 4's `ScanLogSource` did with
SQL. The producer neither knows nor cares how far the consumer has read — the dependency points
from consumer to producer, the way a Kafka consumer depends on a topic rather than the topic on the
consumer — so either can be down without the other noticing. On restart analytics replays one window
from the end of the feed, as before. The settle delay that week 4 introduced (sequence order is not
commit order) still applies, and is now a comparison between two services' clocks; on one machine
they are the same clock.

A message broker would be the textbook alternative: a relay publishing the outbox to RabbitMQ or
Kafka. It would add a container, a client library and a delivery-semantics story to buy what the
feed already provides here, and it is what the event-driven week is for.

**It is still a poll.** The week 4 review called polling for scans reasonable and expected it to
change. Here is what changed and what did not:

- **Changed:** analytics no longer reads another service's table. It calls an API that the
  transaction service owns, and the table behind that API can change without analytics knowing.
- **Not changed:** analytics still asks "anything after id *N*?" every 50 ms (at once again if the
  batch was full), and nothing is pushed to it.

That is a choice, not an oversight:

- **The consumer holds the cursor.** That is what lets either side restart without the other
  noticing, and lets analytics replay a window after a restart.
- **It is cheap.** An empty poll is one primary-key range query. A scan reaches the window 250–300 ms
  after it happens: the settle delay plus at most one poll interval.

The poll could go without a broker. A server-streaming `ScanFeed.Subscribe(afterId)` would let the
transaction service push each scan after its commit, but the producer would then have to track its
subscribers and resend whatever a reconnecting one missed. That is a broker's job, and putting a
broker behind the outbox is the natural next step for an event-driven design.

## Why gRPC (still)

Unchanged from week 4 for the gateway → service hop: typed contracts generated from one `.proto`,
HTTP/2 multiplexing, deadlines and status codes. Week 5 uses the same for service → service calls,
which brings two things the gateway hop did not need:

- **Internal contracts kept separate from public ones.** `StockService` and `ScanFeed` are their
  own gRPC services, not extra methods on `InventoryService` and `TransactionService`, so the rule
  "the gateway never calls them" is a rule about which stubs it may hold — checkable by ArchUnit.
- **A dependency's failure is a new kind of error.** `ServiceUnavailableException` (chassis)
  crosses the wire as `UNAVAILABLE`, which the gateway already answered with 503; its error code
  names the dependency (`INVENTORY_UNAVAILABLE`, `CATALOG_UNAVAILABLE`).

## Architectural characteristics

| Characteristic | Week 4 (service-based) | Week 5 (microservices) |
| -------------- | ---------------------- | ---------------------- |
| **Data independence** | Lost: one schema, linked into every service as `shared-db` | **Regained**: each service's tables are private, behind its RPCs, so changing them is a local change |
| **Deployability** | Five jars, but a table change means redeploying every service that links `shared-db` | Five jars; four private schemas. Any one service can be stopped and redeployed alone (`./run-local.sh <service>`), and fault injection did exactly that to each of them under load |
| **Fault isolation** | Analytics, catalog or inventory could be down while checkout carried on; the shared database could not | Catalog or analytics down: **0** checkout errors. Inventory down: only `complete` fails, fast (503), and the system repairs itself. Each database now belongs to one service |
| **Consistency** | One ACID transaction per sale | A saga: atomic once it settles, but not isolated. Checked across both databases after every run, including the crashes |
| **Recoverability** | Nothing to recover: a crash rolls the sale back | Sagas cut off by a crash or an outage are finished by the recovery job (within ~40 s); a restarted service is reachable again in 0.1–0.2 s |
| **Performance, default load** | 89 tx/s | 76 tx/s: `complete` is now three local transactions and a service call |
| **Performance, stress** | 78 tx/s, capped by the hot row lock | 77 tx/s, the same cap. The waiting moved from START/SCAN into COMPLETE; 3 timeouts in 18 524 completes |
| **Scalability** | Services scale alone, but all share one database server | Services and their databases scale alone, and checkout's database work is split over two servers. The hot row still caps checkout, whatever is scaled |
| **Modularity** | Maven modules and ArchUnit | The same, plus an enforced call graph (which service may hold which stub) |
| **Simplicity** | Six processes, one database to inspect | Nine processes (five JVMs, four databases); a five-state saga, a recovery job, idempotency keys and an outbox. "What happened to this sale?" now spans two databases |

Performance figures are two-round averages from the same session (*Load test results*).

## Load test results

The report files and the per-run tables are in the README (*Results*). Week 4 was re-run in the same session,
interleaved with week 5, on fresh databases each time. Averages over the two rounds:

| | Default: week 4 | Default: week 5 | Stress: week 4 | Stress: week 5 |
| - | - | - | - | - |
| Transactions | 5 354 (89.0/s) | 4 550 (75.7/s) | 9 398 (77.7/s) | 9 260 (76.8/s) |
| START mean | 3.8 ms | 4.4 ms | 58 ms | 9.0 ms |
| SCAN mean | 7.5 ms | 8.3 ms | 64 ms | 17.9 ms |
| COMPLETE mean | 29 ms | 42 ms | 561 ms | 1 101 ms |
| COMPLETE p95 / p99 | 68 / 103 ms | 98 / 171 ms | 1.84 / 3.12 s | 2.69 / 4.24 s |
| Errors | 0 | 0 | 0 | 3 (of 18 524 completes) |

**Default load: the saga costs about 13 ms per sale.** `complete` is now three local transactions in two
databases plus a service call, where week 4 had one transaction:

1. freeze, in transaction-db;
2. deduct, in inventory-db, over gRPC;
3. mark completed, in transaction-db.

Starts and scans are unchanged within noise. This laptop's CPU is saturated even at 10 stations (see
*"2 ms per call is high?"* below), so the extra work also costs throughput: −20 % and −11 % in the two
rounds.

**Stress: the same throughput, but the waiting moved.** The load client is a closed loop with no think
time, so Little's law gives throughput exactly: *X = N / R*, where *N* is the 100 stations and *R* is one
station's cycle (a start, about 10.5 scans, a complete).

- Week 4: *R* = 58 + 10.4 × 64 + 561 ≈ 1 284 ms, so *X* ≈ 78/s (measured 77.7/s).
- Week 5: *R* = 9 + 10.5 × 18 + 1 101 ≈ 1 299 ms, so *X* ≈ 77/s (measured 76.8/s).

The cycle is the same length; only its split changed. In both weeks the binding limit is the row lock on
`SKU-000001`, which is in about two in three baskets. Every such sale passes through that lock one at a
time, and splitting the database does not change how long each sale holds it. What changed is where the
other stations wait:

- **In week 4**, a `complete` waiting for the lock holds one of the transaction service's 50 database
  connections, and starts and scans need those connections too. Little's law puts 44 stations in
  `complete` on average and 52 scanning.
- **In week 5**, the lock wait holds an inventory connection instead. 85 stations park in `complete` and
  only 15 are scanning.

The scan path behaves like one saturated resource. In every run in this document a scan's latency is
proportional to how many stations are scanning at once, at 1.0–1.4 ms per station. So fewer stations
scanning means faster scans: 18 ms against 64 ms. That is also the connections confound noted under
*Thread pools and connections*. Week 5's extra 50 connections change where the stations wait, not how many
sales get through. More connections could not have raised week 4's throughput either, because more sales
waiting at one lock do not get through it faster.

**The 3 errors are the saga's deadline.** Three stock steps waited longer than 9 s for the hot lock. The
saga gave up and compensated, and the restore also queued for the lock (1–3 s), so the load client's
own 10 s timeout fired before the 503 arrived. The stock was put back, and the invariant counts those three
baskets as not sold. Week 4 has no deadline on that wait; its longest `complete` simply took 7.7 s.

## Fault injection

`fault-injection.sh <service>` does the following, once per service, each on fresh databases (reports in
`reports/fault-injection/`):

1. drives the default load (10 stations × 60 s);
2. kills the service with `kill -9` at +20 s. There are no shutdown hooks, so its in-flight requests and
   sagas are simply cut off;
3. restarts it 15 s later;
4. measures how long until a request through the gateway that needs the service succeeds again;
5. waits for saga recovery, then checks the invariant.

| Killed | Sales | Failed while it was down | Time to recover | Recovery | Invariant |
| ------ | ----- | ------------------------ | --------------- | -------- | --------- |
| inventory | 3 042 | 1 837 completes (fast 503, mean 44 ms); 0 starts, 0 scans | 0.1 s | 1 836 sagas compensated by the recovery job, the last by its own request | holds |
| transaction | 3 118 | 247 742 starts (the client retries without pausing), 6 scans, 3 completes | 0.2 s | the 3 sagas the crash cut off, compensated after the restart | holds |
| analytics | 4 606 | **0** | 0.2 s | — | holds |
| catalog | 4 748 | **0** | 0.2 s | — | holds |

- **Inventory: the saga's failure path, 1 837 times.** With inventory gone, `DeductStock` fails at once
  (`UNAVAILABLE`), the in-request `RestoreStock` fails too, and the saga is left `COMPENSATING` with a 503
  to the client. Starts and scans never touch inventory and carried on. When the load ended, 1 106 sagas
  were still waiting out their 30 s; 16 s later none were. The recovery job logged 1 836 compensations in
  all, and nothing was left in flight, so the remaining one was compensated by its own request, the only
  other actor that can. None of those baskets was sold, and no stock was lost.
- **Transaction: the orchestrator dies mid-saga.** Three sagas were between freeze and completion. The
  restarted service found them stuck and compensated them. Nothing works while the transaction service is
  down, because it *is* checkout: the gateway answered every start with an immediate 503, and the client's
  stations retried without pausing, hence the 247 742.
- **Analytics and catalog: independent deployment.** Neither run had a checkout error. Only their own
  endpoints (`/analytics/popular-items`, `/items`) answered 503 while they were down. Checkout's item
  cache is what carries a catalog outage.

**What fault injection found: reconnecting took 6.6 s.** The first fault runs showed requests still failing
6.6 s after the restarted service was listening. While a server is unreachable, a gRPC channel retries
with exponential backoff (1 s, 1.6 s, 2.6 s, … up to two minutes) and fails calls at once in between. A
dead port on the same machine refuses instantly, so a 15 s outage climbs well into that schedule.

The fix is a watchdog in the chassis's `StubPool` and in the gateway's `ServiceClients`. Every 500 ms it
calls `resetConnectBackoff()` on any channel in `TRANSIENT_FAILURE`, so a channel tries again within half a
second of its server coming back. Time to recover went from 6.6 s to 0.1–0.2 s. The pre-fix
transaction-service run is kept in `reports/fault-injection/before-reconnect-fix/`:

| | Before the fix | After |
| - | - | - |
| Failed starts | 346 632 | 247 742 |
| Failed scans | 1 548 | 6 |
| Sales | 2 295 | 3 118 |

## Two questions from the week 4 review

The review of week 4 asked three questions. One, about polling for scans, is answered under *Scans to
analytics* above. The other two are answered here by experiments. Each is kept apart from the comparison
above, with its reports under `reports/experiments/`, and neither changes the defaults that comparison
used.

### "Locks on checkout are the slow part? How could you address this?"

Yes, under stress: in week 5, 85 % of a station's cycle is `complete`, waiting for the row lock on
`SKU-000001`. A lock's throughput is one over the time each sale holds it, and that time is not the
sale's own SQL. Rows are locked in SKU order, so `SKU-000001` is locked *first*. Its holder then goes on to
lock the rest of its basket, queuing behind other sales for `SKU-000002`, `-000003`, …, all while holding
the hottest lock.

Any fixed order prevents deadlock, so the experiment reverses it:
`supermarket.inventory.lock-order=SKU_DESCENDING` locks `SKU-000001` last, so it is held only for its own
update and the commit. The runs:

- four stress runs, alternating ascending, descending, descending, ascending, and one default run of each
  order, all on fresh databases;
- each run's inventory log confirms the order it used, and no service log reported a deadlock;
- the invariant held in every run;
- reports in `reports/experiments/lock-order/{ascending,descending}/`.

| Stress, 100 × 120 s | Ascending (default) | Descending |
| ------------------- | ------------------- | ---------- |
| Transactions | 8 995 / 9 062 | **10 444 / 10 128** (+14 %) |
| COMPLETE mean | 1 135 / 1 128 ms | **306 / 317 ms** |
| COMPLETE p99 | 4.44 / 4.50 s | **1.94 / 2.14 s** |
| START / SCAN mean | 9.7 / 18.2 ms | 53 / 77 ms |
| Errors | 2 timeouts / 0 | 0 / 0 |

At the default load the order makes no difference: 4 869 against 4 919 transactions, because 10 stations
never queue on that lock.

`complete` got 3.6× faster, but throughput rose only 14 %, because the waiting moved on to the next limit.
With checkout fast, 69 stations are scanning at once, and at 1.1 ms each a scan takes 77 ms. Little's law
again: 53 + 10.5 × 77 + 311 ≈ 1 172 ms, so about 85/s, as measured. The scan path is limited by the CPU,
and most of that is one query (next question). At about 900 scans/s it is the same ceiling that week 4
reaches at the default load.

It is not the default, for two reasons:

- Week 4 would gain the same from it (it locks in the same order), so switching it on for week 5 only
  would make the week-over-week comparison about tuning.
- Descending SKU order means "hottest last" only because the catalog's numbering matches the load
  client's Zipf ranking. Ordering by *measured* popularity would not be safe: the order must be the same
  for every concurrent sale, and a ranking that moves would let two sales disagree.

Other ways to shorten or avoid the hot lock, not built:

- **Split the hot row.** Keep `SKU-000001`'s stock in several rows and let each sale take from one, so
  sales of it proceed in parallel; the low-stock report sums the rows. A sale that finds its row empty
  must try another.
- **Lock one row at a time.** Commit each SKU's decrement on its own inside the deduct, recording each in
  the ledger, and undo the earlier ones if a later one fails: the saga's own idea, applied inside
  inventory. No lock is then held across a basket. The cost is that a basket's stock is taken row by row,
  but the saga is already not isolated.
- **Batch the hot row.** Queue the decrements for hot SKUs and apply many sales in one `UPDATE` (group
  commit).

### "2 ms per call is high? Probably lower on a distributed deployment?"

Yes on both counts, as far as one laptop can show. Week 4's "about two milliseconds" was not a measurement
of a call. It was the rise in mean START and SCAN latency from week 3 to week 4, from two different
sessions, and SCAN had gained an insert as well. Timed on its own here, mean (p99), with a probe making one
call at a time from its own JVM. The probe and its script are `experiments/hop-cost/`, and the results are
in `reports/experiments/hop-cost/`. The committed run did the probe phases and the CPU check as two
invocations; the script does both in one.

| One call | Machine idle | Default load (CPU 96 % busy) | Stress (CPU 95 % busy) |
| -------- | ------------ | ---------------------------- | ---------------------- |
| Bare gRPC call, back to back | **0.08 ms** (0.16) | | |
| Bare gRPC call, one per ms | 0.30 ms (1.0) | 0.43 ms (1.1) | 0.39 ms (1.3) |
| Real `GetItem`, back to back | 0.52 ms (0.8) | | |
| Real `GetItem`, one per ms | 1.67 ms (5.0) | 1.63 ms (4.3) | 2.00 ms (6.5) |

What the rows measure:

- **The bare call** goes to a dummy server that answers at once. It is everything a hop costs, and
  nothing else: serialise, HTTP/2 over loopback, deserialise, and thread hand-offs on both sides.
- **The real call** is the catalog service and one primary-key lookup in catalog-db.
- **"One per ms"** lets the threads fall asleep between calls, and each call pays to wake them. For the
  real call that includes a PostgreSQL backend inside Docker's VM, reached through Docker's port
  forwarding.

What the numbers say:

- **A hop costs 0.1–0.4 ms here, not 2 ms**, and a busy machine barely changes that.
- **The machine is CPU-bound even at the default load.** The 10 stations send requests back to back, so
  "default" is not light: the CPU was 96 % busy at 10 stations and 95 % at 100. Under stress, Docker's VM
  (the four databases) used about 3.6 of the 8 cores, Docker's host-side backend (which forwards every
  database connection into the VM) about 1.1, and the JVMs most of the rest. On a CPU-bound machine,
  extra work per request costs more than its own duration, because every request also waits for the CPU
  behind everyone else's extra work. That is how week 4's additions per request (a gateway→service hop, a
  second process, a `scan_log` insert) showed up as about 2 ms.
- **Most of that CPU is one query.** At 10 stations, transaction-db alone used 1.7–2.3 cores
  (`where-the-cpu-goes-*.txt`). In a 40 s run, `transaction_items` was read by sequential scan 35 664
  times, 518 million rows in all: about once per scan and per complete, each of which reloads the basket's
  lines by `tx_id`. That column has no index, deliberately, since week 1, so that every week runs the same
  SQL. Week 4 runs the same query. The index is the obvious next fix after the lock order, and it would
  help weeks 1–5 alike.
- **Distributed, the cost per request would probably be lower**, as long as the hosts are close together.
  Each service would have its own CPU, so most of the contention that dominates these numbers would go.
  The network would add a round trip per hop, typically a few hundred microseconds within one datacenter
  (not measured here). Far-apart hosts change that: a cross-region hop costs tens of milliseconds.

Week 4's analysis, and the first version of this one, said the opposite: that a network would only *add*
to the hop's cost measured here. For this setup that was wrong. The larger cost is sharing one CPU. (The
probe is not free either: the stress run with it attached completed 8 553 sales with 5 timeouts, so it is
not part of the comparison.)

## Design decisions

### Thread pools and connections

The transaction service keeps the 200 gRPC worker threads and 50 Hikari connections held constant
since week 1. The **inventory service** now gets the same 200/50, because the work that holds the hot
row locks — the stock decrement — now runs there. Catalog and analytics stay small (16 threads, 5
connections). Because every service has its own PostgreSQL, there is no longer one 100-connection
server limit to share; the system as a whole has more connections than week 4 (50 + 50 + 5 + 5 on
four servers, against 65 on one). That is part of what a database per service is, but it is also a
confound in the stress comparison, discussed with the results.

### Seeding

The catalog seeds its items in one transaction on an empty database. Inventory, on an empty
database, asks the catalog for the item list — retrying until the catalog answers with a non-empty
list, which the single-transaction seed makes all-or-nothing — and seeds 10 000 units per item. The
transaction service warms its catalog cache at startup and fetches single items on a miss.
`run-local.sh` starts them in that order and waits for each seed's log line.

### Schemas

Each service's schema is a file applied by its own PostgreSQL on first start (`ddl-auto=none`).
`transactions` and `transaction_items` keep weeks 1–4's columns, including the missing index on
`transaction_items.tx_id`, so the basket SQL costs what it did. New: two saga columns on
`transactions` and a partial index on in-flight sagas only (normally none), so the recovery job's scan
is cheap without indexing every completed row; `sale_lines` ids come from a pooled sequence so
Hibernate batches a sale's lines into one round trip while the row locks are held.

### Lock order

Stock rows are locked in SKU order, as in weeks 1–4 — the stable order that rules out deadlock. One
consequence became visible this week: the hottest SKU under the Zipf workload, `SKU-000001`, also
sorts first, so every sale that contains it — about two in three (it gets 12.2 % of scans, and a
basket holds 1–20 of them) — takes the most contended lock *first* and holds it while it locks and
updates the rest of its basket. Any consistent order prevents deadlock, and
`supermarket.inventory.lock-order=SKU_DESCENDING` takes the hottest lock last instead. That is the
experiment under *Two questions from the week 4 review*: +14 % stress throughput, `complete` 3.6× faster.
The default stays ascending, so the comparison with weeks 1–4 stays about the architecture.

### Known limitations

- **No isolation between saga steps** (above): low-stock reports can see stock taken for a sale
  that is then compensated.
- **A stuck saga keeps its basket for 30 s.** After a crash or an inventory outage, a basket is
  unusable until the recovery job reaches it. Shortening `stuck-after` shortens that, at the price
  of a smaller margin over the 9 s stock deadline.
- **Under stress, the saga's deadline turns the longest lock waits into errors.** Week 4 let a
  `complete` wait as long as the lock queue took; week 5 gives up on the stock step at 9 s and
  compensates: 3 of 18 524 stress completes, and none with the hottest lock taken last. The alternative is
  a longer deadline than the client's own timeout.
- **The orchestrator's database is a single point of failure for checkout.** The saga state lives in
  transaction-db by design; if that database is down, nothing can be started, scanned or completed.
- **The outbox and the ledger grow without bound** (`scan_events`, `sales`, `sale_lines`). Both need
  a retention job; the ledger's rows can go once their sale is final.
- **The caches assume an immutable catalog** (see *Data ownership*).
- **The settle delay compares two services' clocks.** On separate hosts, skew would eat into it; a
  commit-ordered feed (logical replication, or a broker fed by a relay) would remove the heuristic.
- **Everything is on one machine, and its CPU is saturated at both loads.** Per-request costs here are
  mostly contention for the CPU, not the calls themselves (0.1–0.4 ms each; see *"2 ms per call is high?"*).
  On separate hosts most of that contention would go, and each hop would add a network round trip.
- **The gateway is still a single point of failure**, with no service discovery, TLS,
  authentication or retries — single-machine coursework, as in week 4.
