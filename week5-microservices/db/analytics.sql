-- The analytics service's database (analytics-db). Applied by PostgreSQL on first start; the
-- service runs ddl-auto=none. No other service has credentials for this database.

CREATE TABLE popular_items (
    sku        varchar(32)  PRIMARY KEY,
    name       varchar(255) NOT NULL,
    scan_count integer      NOT NULL,
    item_rank  integer      NOT NULL
);
