package com.supermarket.transaction.inventory.model;

/** One item that has fallen below the reporting threshold. */
public record LowStockAlertView(String sku, String name, int currentStock, int threshold) {
}
