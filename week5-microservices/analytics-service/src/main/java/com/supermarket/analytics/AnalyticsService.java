package com.supermarket.analytics;

import com.supermarket.analytics.model.PopularItemsView;

/**
 * Serves the most-scanned items over a hopping window. Scans arrive not through
 * this interface but via {@link ScanFeedSource}, which pages through the transaction
 * service's scan feed into the pipeline.
 */
public interface AnalyticsService {

    /** The current top-{@code limit} ranking with its window bounds. */
    PopularItemsView getPopular(int limit);
}
