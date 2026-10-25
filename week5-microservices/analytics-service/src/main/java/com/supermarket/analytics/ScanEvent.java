package com.supermarket.analytics;

import java.time.Instant;

/**
 * One scanned unit, as the transaction service's scan feed reports it. The id is the
 * feed's own sequence, so it gives this consumer a total order and a cursor.
 */
record ScanEvent(long id, String sku, Instant scannedAt) {
}
