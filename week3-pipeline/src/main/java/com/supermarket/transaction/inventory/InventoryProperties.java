package com.supermarket.transaction.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code supermarket.inventory.*}. */
@ConfigurationProperties(prefix = "supermarket.inventory")
public record InventoryProperties(int lowStockThreshold) {

    public InventoryProperties {
        if (lowStockThreshold <= 0) {
            lowStockThreshold = 50;
        }
    }
}
