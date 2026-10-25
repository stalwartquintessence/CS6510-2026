package com.supermarket.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code supermarket.inventory.*}.
 *
 * @param lowStockThreshold default threshold for low-stock reports
 * @param initialStock      units per SKU when the stock table is first seeded (was the
 *                          catalog's {@code stock-per-item} in weeks 1–4)
 * @param seedTimeout       how long to wait for the catalog service when seeding
 */
@ConfigurationProperties(prefix = "supermarket.inventory")
public record InventoryProperties(int lowStockThreshold, int initialStock, Duration seedTimeout) {

    public InventoryProperties {
        if (lowStockThreshold <= 0) {
            lowStockThreshold = 50;
        }
        if (initialStock <= 0) {
            initialStock = 10000;
        }
        if (seedTimeout == null || seedTimeout.isNegative() || seedTimeout.isZero()) {
            seedTimeout = Duration.ofMinutes(2);
        }
    }
}
