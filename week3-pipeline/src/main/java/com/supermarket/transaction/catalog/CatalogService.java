package com.supermarket.transaction.catalog;

import com.supermarket.domain.ItemSnapshot;

import java.util.List;

/** Serves the item catalog and owns first-run seeding of it. */
public interface CatalogService {

    /** The full catalog, SKU order. */
    List<ItemSnapshot> catalog();

    /** Seed {@code size} items if the catalog is empty; otherwise do nothing. */
    void seedIfEmpty(int size, int stockPerItem);
}
