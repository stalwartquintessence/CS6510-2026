package com.supermarket.analytics.model;

import java.time.Instant;
import java.util.List;

/**
 * The current ranking plus the window bounds that produced it, so a client can
 * audit which scans the ranking covers.
 */
public record PopularItemsView(
        int windowSize,
        int slideInterval,
        long windowStart,
        long windowEnd,
        Instant computedAt,
        List<PopularItemView> items) {
}
