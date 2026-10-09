package com.supermarket.persistence;

import com.supermarket.domain.ScanEvent;

import java.time.Instant;
import java.util.List;

/**
 * The {@code scan_log} table: the one channel between the transaction service
 * (which appends) and the analytics service (which tails it). The two services
 * never call each other; this table is the whole of their coupling.
 */
public interface ScanLogDao {

    /** Record one scanned unit. Joins the caller's transaction, so a rolled-back scan leaves no trace. */
    void append(String sku, Instant scannedAt);

    /** Up to {@code limit} events with id strictly greater than {@code afterId}, oldest first. */
    List<ScanEvent> findAfter(long afterId, int limit);

    /** The newest event id, or 0 if the log is empty. */
    long maxId();
}
