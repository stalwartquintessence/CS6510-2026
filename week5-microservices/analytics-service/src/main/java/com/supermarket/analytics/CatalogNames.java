package com.supermarket.analytics;

import java.util.Optional;

/**
 * Item names for the ranking, from the catalog service. In week 4 the Enrich stage
 * read them from the shared {@code items} table; the catalog is now another service's
 * data, reached only through its RPC.
 */
interface CatalogNames {

    /**
     * @return the item's name, or empty if the catalog does not hold the SKU
     * @throws RuntimeException if the catalog could not be asked (the Enrich stage then
     *                          skips this window and the next one retries)
     */
    Optional<String> nameOf(String sku);
}
