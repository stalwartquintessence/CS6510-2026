package com.supermarket.analytics;

import java.util.List;

/** Rank → Enrich: the top-N SKUs of one window, best first. */
record Ranking(long windowStart, long windowEnd, List<RankedSku> entries) {

    Ranking {
        entries = List.copyOf(entries);
    }
}
