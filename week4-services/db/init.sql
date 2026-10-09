-- The one schema of the one shared database. Created by PostgreSQL on first start
-- (mounted into /docker-entrypoint-initdb.d); no service creates or alters tables.
--
-- Same tables, columns and constraints that Hibernate's ddl-auto=update generated in
-- weeks 1-3 (including no index on transaction_items.tx_id), so the SQL the services run
-- costs what it cost then. The one addition is scan_log, which carries scans from the
-- transaction service to the analytics service.

CREATE TABLE items (
    sku    varchar(32)      PRIMARY KEY,
    name   varchar(255)     NOT NULL,
    price  double precision NOT NULL,
    stock  integer          NOT NULL
);

CREATE TABLE transactions (
    id           varchar(40)      PRIMARY KEY,
    station_id   varchar(255)     NOT NULL,
    status       varchar(16)      NOT NULL,
    total        double precision NOT NULL,
    created_at   timestamptz      NOT NULL,
    completed_at timestamptz
);

CREATE TABLE transaction_items (
    id          bigserial        PRIMARY KEY,
    sku         varchar(32)      NOT NULL,
    name        varchar(255)     NOT NULL,
    unit_price  double precision NOT NULL,
    quantity    integer          NOT NULL,
    tx_id       varchar(40)      NOT NULL REFERENCES transactions (id)
);

CREATE TABLE popular_items (
    sku        varchar(32)  PRIMARY KEY,
    name       varchar(255) NOT NULL,
    scan_count integer      NOT NULL,
    item_rank  integer      NOT NULL
);

-- Appended by the transaction service on every scan, tailed by the analytics service.
CREATE TABLE scan_log (
    id         bigserial   PRIMARY KEY,
    sku        varchar(32) NOT NULL,
    scanned_at timestamptz NOT NULL
);
