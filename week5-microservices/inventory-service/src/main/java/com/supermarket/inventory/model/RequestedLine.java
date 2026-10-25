package com.supermarket.inventory.model;

/** One line of a sale as the checkout saga asks for it: this many units of this SKU. */
public record RequestedLine(String sku, int quantity) {
}
