# Self-Checkout Microservices Architecture Analysis

> All figures are from one M1 Pro laptop on AC power, every service and database on the same machine.

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

_Results write-up in progress: the raw load-client reports are in `reports/` (same-session week 4 baselines in `../week4-services/reports/`) and `reports/fault-injection/`._

## Load test results

_Results write-up in progress: the raw load-client reports are in `reports/` (same-session week 4 baselines in `../week4-services/reports/`) and `reports/fault-injection/`._

## Fault injection

_Results write-up in progress: the raw load-client reports are in `reports/` (same-session week 4 baselines in `../week4-services/reports/`) and `reports/fault-injection/`._

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
updates the rest of its basket. Any consistent order prevents
deadlock; ordering by descending SKU would take the hottest lock last and hold it for the shortest
time. It was not changed, so the comparison with weeks 1–4 stays about the architecture.

### Known limitations

- **No isolation between saga steps** (above): low-stock reports can see stock taken for a sale
  that is then compensated.
- **A stuck saga keeps its basket for 30 s.** After a crash or an inventory outage, a basket is
  unusable until the recovery job reaches it. Shortening `stuck-after` shortens that, at the price
  of a smaller margin over the 9 s stock deadline.
- **Under stress, the saga's deadline turns the longest lock waits into errors.** Week 4 let a
  `complete` wait as long as the lock queue took; week 5 gives up on the stock step at 9 s and
  compensates (see the results). The alternative is a longer deadline than the client's own timeout.
- **The orchestrator's database is a single point of failure for checkout.** The saga state lives in
  transaction-db by design; if that database is down, nothing can be started, scanned or completed.
- **The outbox and the ledger grow without bound** (`scan_events`, `sales`, `sale_lines`). Both need
  a retention job; the ledger's rows can go once their sale is final.
- **The caches assume an immutable catalog** (see *Data ownership*).
- **The settle delay compares two services' clocks.** On separate hosts, skew would eat into it; a
  commit-ordered feed (logical replication, or a broker fed by a relay) would remove the heuristic.
- **Everything is on one machine.** Every hop is loopback, so the cost of the extra calls measured
  here is a floor; a real network adds to every one of them.
- **The gateway is still a single point of failure**, with no service discovery, TLS,
  authentication or retries — single-machine coursework, as in week 4.
