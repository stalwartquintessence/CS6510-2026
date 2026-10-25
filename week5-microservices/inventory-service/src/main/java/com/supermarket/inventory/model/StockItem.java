package com.supermarket.inventory.model;

/** One SKU's stock row. The name is a copy taken from the catalog at seeding, so low-stock reports need no call. */
public record StockItem(String sku, String name, int stock) {
}
