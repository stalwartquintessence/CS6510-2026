package com.supermarket.domain;

import java.time.Instant;

/**
 * One scanned unit, as recorded in the shared {@code scan_log} table. The id is
 * the table's sequence, so it gives every consumer the same total order.
 */
public record ScanEvent(long id, String sku, Instant scannedAt) {
}
