package com.supermarket.catalog;

import com.supermarket.catalog.model.ItemView;

import java.util.List;

/** Serves the item catalog and owns first-run seeding of it. */
public interface CatalogService {

    /** The full catalog, SKU order. */
    List<ItemView> catalog();

    /**
     * One item, for the services that cache the catalog (transaction: names and prices
     * for scans; analytics: names for the ranking).
     *
     * @throws com.supermarket.support.error.ItemNotFoundException if the SKU is unknown
     */
    ItemView item(String sku);

    /** Seed {@code size} items if the catalog is empty; otherwise do nothing. */
    void seedIfEmpty(int size);
}
