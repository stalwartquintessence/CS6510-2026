package com.supermarket.transaction.model;

import java.time.Instant;

/** Snapshot of a basket, returned by start and status lookups. */
public record BasketView(
        String transactionId,
        String stationId,
        String status,
        int itemCount,
        double runningTotal,
        Instant startedAt) {
}
