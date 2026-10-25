-- The inventory service's database (inventory-db). Applied by PostgreSQL on first start; the
-- service runs ddl-auto=none. No other service has credentials for this database.

-- Live stock, seeded from the catalog service on first start. The name is a copy, so low-stock
-- reports need no call to the catalog.
CREATE TABLE stock_items (
    sku    varchar(32)  PRIMARY KEY,
    name   varchar(255) NOT NULL,
    stock  integer      NOT NULL
);

-- The saga ledger. One row per sale id (one per completion attempt), inserted by whichever of
-- DeductStock / RestoreStock arrives first; the primary key is what makes both idempotent.
CREATE TABLE sales (
    sale_id    varchar(64) PRIMARY KEY,
    status     varchar(16) NOT NULL,   -- APPLIED | REVERTED
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

-- What each sale did to each SKU. applied <= requested: stock is floored at zero, and
-- compensation puts back applied, never requested.
CREATE SEQUENCE sale_lines_seq INCREMENT BY 50;
CREATE TABLE sale_lines (
    id         bigint      PRIMARY KEY,
    sale_id    varchar(64) NOT NULL REFERENCES sales (sale_id),
    sku        varchar(32) NOT NULL,
    requested  integer     NOT NULL,
    applied    integer     NOT NULL
);
CREATE INDEX sale_lines_sale_id ON sale_lines (sale_id);
