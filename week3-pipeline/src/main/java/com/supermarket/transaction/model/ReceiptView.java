package com.supermarket.transaction.model;

import java.time.Instant;
import java.util.List;

/** A completed sale. */
public record ReceiptView(
        String transactionId,
        String stationId,
        int itemCount,
        double totalAmount,
        Instant startedAt,
        Instant completedAt,
        List<ReceiptLineView> lines) {
}
