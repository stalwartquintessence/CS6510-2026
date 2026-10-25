-- The transaction service's database (transaction-db). Applied by PostgreSQL on first start;
-- the service runs ddl-auto=none. No other service has credentials for this database.
--
-- transactions and transaction_items keep the columns and constraints of weeks 1-4 (including
-- no index on transaction_items.tx_id), so the SQL costs what it cost then. Added for week 5:

CREATE TABLE transactions (
    id              varchar(40)      PRIMARY KEY,
    station_id      varchar(255)     NOT NULL,
    status          varchar(16)      NOT NULL,
    total           double precision NOT NULL,
    created_at      timestamptz      NOT NULL,
    completed_at    timestamptz,
    -- The checkout saga: which completion attempt this is (each is its own sale id in the
    -- inventory ledger), and when the status last changed (how recovery spots a stuck saga).
    saga_attempt    integer          NOT NULL DEFAULT 0,
    saga_updated_at timestamptz
);

-- Only in-flight sagas (normally none, at most one per station) are indexed, so the recovery
-- job's scan is cheap without adding an index entry for every OPEN or COMPLETED row.
CREATE INDEX transactions_in_flight ON transactions (saga_updated_at)
    WHERE status IN ('COMPLETING', 'COMPENSATING');

CREATE TABLE transaction_items (
    id          bigserial        PRIMARY KEY,
    sku         varchar(32)      NOT NULL,
    name        varchar(255)     NOT NULL,
    unit_price  double precision NOT NULL,
    quantity    integer          NOT NULL,
    tx_id       varchar(40)      NOT NULL REFERENCES transactions (id)
);

-- The outbox: one row per scanned unit, written in the scan's own transaction and read by the
-- analytics service through the ScanFeed RPC. Week 4's scan_log, made private.
CREATE TABLE scan_events (
    id         bigserial   PRIMARY KEY,
    sku        varchar(32) NOT NULL,
    scanned_at timestamptz NOT NULL
);
