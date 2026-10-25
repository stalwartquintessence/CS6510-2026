package com.supermarket.transaction.domain;

import java.time.Instant;

/**
 * One scanned unit, as recorded in this service's {@code scan_events} outbox. The id
 * is the table's sequence, so it gives the analytics service a total order to read in.
 */
public record ScanEvent(long id, String sku, Instant scannedAt) {
}
