package com.supermarket.transaction.model;

/** Outcome of scanning one unit. */
public record ScanView(
        String transactionId,
        String sku,
        String name,
        double unitPrice,
        int itemCount,
        double runningTotal) {
}
