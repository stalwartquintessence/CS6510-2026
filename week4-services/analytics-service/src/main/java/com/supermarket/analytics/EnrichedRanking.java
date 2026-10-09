package com.supermarket.analytics;

import com.supermarket.domain.PopularItemRow;

import java.util.List;

/** Enrich → Publish: a ranking with item names resolved, ready to persist. */
record EnrichedRanking(long windowStart, long windowEnd, List<PopularItemRow> rows) {

    EnrichedRanking {
        rows = List.copyOf(rows);
    }
}
