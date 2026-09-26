package com.supermarket.transaction.inventory;

import com.supermarket.domain.ItemSnapshot;
import com.supermarket.transaction.inventory.model.LowStockView;

import java.util.Optional;

/**
 * Owns stock: the only module allowed to change it, and the only one that
 * reports on it. Transactions ask it to decrement; nothing else may.
 */
public interface InventoryService {

    /** The configured default reporting threshold. */
    int defaultThreshold();

    /** Look up an item for scan-time price and name resolution. */
    Optional<ItemSnapshot> findItem(String sku);

    /**
     * Decrement stock for a completed sale, under a row lock.
     *
     * <p>Must run inside the caller's transaction — enforced, not merely
     * documented, by {@code Propagation.MANDATORY} on the implementation.
     */
    void decrement(String sku, int quantity);

    /** Items below {@code thresholdOverride}, or below the configured default. */
    LowStockView lowStock(Integer thresholdOverride);
}
