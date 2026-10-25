package com.supermarket.transaction;

import com.supermarket.transaction.domain.ItemSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The checkout's read-through copy of the catalog: SKU → name and price.
 *
 * <p>Week 4 read the shared {@code items} row on every scan. The catalog is now the
 * catalog service's data, and asking it on every scan would put a network hop and a
 * second service's availability on the hottest path in the system. Nothing in the
 * contract changes an item after seeding, so caching it indefinitely is exact, not
 * approximate. The cache is warmed with the whole catalog at startup (2000 items, one
 * call); a miss — a cold start with the catalog down, or a SKU added later — asks the
 * catalog for that one item. Once warm, checkout keeps working with the catalog service
 * down. Unknown SKUs are not cached, so an item added later is found on its first scan.
 */
@Component
class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    private final CatalogClient catalog;
    private final Map<String, ItemSnapshot> items = new ConcurrentHashMap<>();

    CatalogCache(CatalogClient catalog) {
        this.catalog = catalog;
    }

    /**
     * @throws com.supermarket.support.error.ItemNotFoundException        if the catalog does not hold the SKU
     * @throws com.supermarket.support.error.ServiceUnavailableException on a miss while the catalog is down
     */
    ItemSnapshot item(String sku) {
        ItemSnapshot cached = items.get(sku);
        if (cached != null) {
            return cached;
        }
        ItemSnapshot fetched = catalog.item(sku);
        items.put(sku, fetched);
        return fetched;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warm() {
        try {
            for (ItemSnapshot item : catalog.all()) {
                items.putIfAbsent(item.sku(), item);
            }
            log.info("Catalog cache warmed: {} items", items.size());
        } catch (RuntimeException e) {
            log.warn("Could not warm the catalog cache ({}); items will be fetched on first scan", e.toString());
        }
    }
}
