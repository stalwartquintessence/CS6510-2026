package com.supermarket.transaction;

import com.supermarket.transaction.domain.ItemSnapshot;

import java.util.List;

/** What the checkout asks the catalog service. An interface so the cache can be tested without a server. */
interface CatalogClient {

    /**
     * @throws com.supermarket.support.error.ItemNotFoundException        if the catalog does not hold the SKU
     * @throws com.supermarket.support.error.ServiceUnavailableException if the catalog could not be asked
     */
    ItemSnapshot item(String sku);

    /** The whole catalog, for warming the cache. */
    List<ItemSnapshot> all();
}
