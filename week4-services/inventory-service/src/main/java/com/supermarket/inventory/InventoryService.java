package com.supermarket.inventory;

import com.supermarket.inventory.model.LowStockView;

/**
 * Reports on stock. In weeks 2–3 this module also resolved items for scans and
 * decremented stock at completion; those two operations belong to the checkout
 * transaction (see the transaction service's {@code StockLedger}) and have moved
 * there, because they must share its database transaction.
 */
public interface InventoryService {

    /** The configured default reporting threshold. */
    int defaultThreshold();

    /** Items below {@code thresholdOverride}, or below the configured default. */
    LowStockView lowStock(Integer thresholdOverride);
}
