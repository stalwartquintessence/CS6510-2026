package com.supermarket.inventory;

import com.supermarket.inventory.model.LowStockView;
import com.supermarket.inventory.model.StockItem;

import java.util.List;

/**
 * Reports on stock and owns its first-run seeding. Changing stock is the saga's
 * business and lives in {@link StockLedger}.
 *
 * <p>Week 4 deliberately kept the stock decrement out of this service, because only a
 * shared database could make it atomic with the basket update. With a database per
 * service that option is gone, so the decrement is back where it belongs and the
 * atomicity across the two databases is the checkout saga's job.
 */
public interface InventoryService {

    /** The configured default reporting threshold. */
    int defaultThreshold();

    /** Items below {@code thresholdOverride}, or below the configured default. */
    LowStockView lowStock(Integer thresholdOverride);

    /** How many SKUs have a stock row. */
    long stockedItems();

    /** Insert the initial stock rows; only ever called on an empty database. */
    void seed(List<StockItem> items);
}
