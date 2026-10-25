-- The catalog service's database (catalog-db). Applied by PostgreSQL on first start; the
-- service runs ddl-auto=none. No other service has credentials for this database.
--
-- Weeks 1-4 kept stock on this row. Stock now belongs to the inventory service.

CREATE TABLE items (
    sku    varchar(32)      PRIMARY KEY,
    name   varchar(255)     NOT NULL,
    price  double precision NOT NULL
);
