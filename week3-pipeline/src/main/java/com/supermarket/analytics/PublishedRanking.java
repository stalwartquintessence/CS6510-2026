package com.supermarket.analytics;

import com.supermarket.domain.PopularItemRow;

import java.time.Instant;
import java.util.List;

/**
 * The pipeline's output as the read side sees it. Bounds, timestamp and rows
 * travel in one immutable value behind one reference, so a reader can never
 * observe new rows with old window bounds (week 2 read the rows from the
 * database and the bounds from separate volatiles, and could).
 */
record PublishedRanking(long windowStart, long windowEnd, Instant computedAt, List<PopularItemRow> rows) {

    PublishedRanking {
        rows = List.copyOf(rows);
    }
}
