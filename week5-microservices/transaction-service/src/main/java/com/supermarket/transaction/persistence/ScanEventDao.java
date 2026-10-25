package com.supermarket.transaction.persistence;

import com.supermarket.transaction.domain.ScanEvent;

import java.time.Instant;
import java.util.List;

/**
 * The {@code scan_events} outbox. The checkout appends to it in the scan's own
 * transaction; the analytics service reads it through the {@code ScanFeed} RPC.
 */
public interface ScanEventDao {

    /** Record one scanned unit. Joins the caller's transaction, so a rolled-back scan leaves no trace. */
    void append(String sku, Instant scannedAt);

    /** Up to {@code limit} events with id strictly greater than {@code afterId}, oldest first. */
    List<ScanEvent> findAfter(long afterId, int limit);

    /** The newest event id, or 0 if there are none. */
    long maxId();
}
