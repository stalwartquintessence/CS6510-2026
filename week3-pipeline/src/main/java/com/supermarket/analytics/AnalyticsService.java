package com.supermarket.analytics;

import com.supermarket.analytics.model.PopularItemsView;

/** Tracks the most-scanned items over a hopping window. */
public interface AnalyticsService {

    /** Record one scanned unit. Called by the transactions layer on every scan. */
    void recordScan(String sku);

    /** The current top-{@code limit} ranking with its window bounds. */
    PopularItemsView getPopular(int limit);
}
