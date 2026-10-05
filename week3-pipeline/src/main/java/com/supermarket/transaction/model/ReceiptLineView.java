package com.supermarket.transaction.model;

/** One line of a completed sale. */
public record ReceiptLineView(String sku, String name, double unitPrice, int quantity) {
}
