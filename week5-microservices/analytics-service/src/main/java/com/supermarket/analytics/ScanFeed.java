package com.supermarket.analytics;

import java.util.List;

/**
 * Where scans come from: the transaction service's outbox, read over its
 * {@code ScanFeed} RPC. An interface so {@link ScanFeedSource} can be driven by an
 * in-memory feed in tests. In week 4 this was {@code ScanLogDao}, a query on a table
 * in the shared database; the analytics service now has no access to that database.
 */
interface ScanFeed {

    /** Up to {@code limit} events with id strictly greater than {@code afterId}, oldest first. */
    List<ScanEvent> readAfter(long afterId, int limit);

    /** The newest event id, or 0 if there are none. */
    long latestId();
}
