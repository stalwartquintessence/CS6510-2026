package com.supermarket.analytics;

import java.util.List;

/**
 * Window → Rank: the SKUs in one hopping window, plus the global scan sequence
 * numbers that bound it. Immutable, so it can cross threads freely.
 */
record WindowSnapshot(long windowStart, long windowEnd, List<String> skus) {

    WindowSnapshot {
        skus = List.copyOf(skus);
    }
}
