package com.supermarket.transaction.inventory.model;

import java.time.Instant;
import java.util.List;

/** Result of a low-stock query, with the threshold that produced it. */
public record LowStockView(int threshold, Instant generatedAt, List<LowStockAlertView> alerts) {
}
