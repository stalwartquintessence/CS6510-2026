package com.supermarket.analytics;

import com.supermarket.analytics.model.PopularItemView;
import com.supermarket.analytics.model.PopularItemsView;
import com.supermarket.domain.PopularItemRow;
import com.supermarket.persistence.PopularItemsDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The analytics service's read side: an adapter onto {@link ScanAnalyticsPipeline}.
 *
 * <p>In week 2 this class <em>was</em> the analytics — every 500th scan ran the
 * recompute on a request thread. Since week 3 all of that happens on the
 * pipeline's own threads; since week 4 the scans do not even arrive from a request
 * thread, but from {@link ScanLogSource}.
 */
@Service
class DefaultAnalyticsService implements AnalyticsService {

    private final ScanAnalyticsPipeline pipeline;
    private final PopularItemsDao popularItemsDao;
    private final int windowSize;
    private final int slideInterval;

    DefaultAnalyticsService(ScanAnalyticsPipeline pipeline,
                            PopularItemsDao popularItemsDao,
                            AnalyticsProperties properties) {
        this.pipeline = pipeline;
        this.popularItemsDao = popularItemsDao;
        this.windowSize = properties.windowSize();
        this.slideInterval = properties.slideInterval();
    }

    /**
     * Serves the latest published ranking from memory. Before the first window
     * has been published (e.g. just after a restart) it falls back to whatever
     * ranking the table still holds, with zero bounds — the same answer week 2
     * gave in that situation.
     */
    @Override
    @Transactional(readOnly = true)
    public PopularItemsView getPopular(int limit) {
        PublishedRanking published = pipeline.latest();
        List<PopularItemRow> ranked = published != null ? published.rows() : popularItemsDao.findRanking();

        int effectiveLimit = Math.max(0, limit);
        List<PopularItemView> items = new ArrayList<>(Math.min(effectiveLimit, ranked.size()));
        for (PopularItemRow row : ranked) {
            if (items.size() >= effectiveLimit) {
                break;
            }
            items.add(new PopularItemView(row.sku(), row.name(), row.scanCount(), row.rank()));
        }

        if (published == null) {
            return new PopularItemsView(windowSize, slideInterval, 0, 0, Instant.now(), items);
        }
        return new PopularItemsView(
                windowSize,
                slideInterval,
                published.windowStart(),
                published.windowEnd(),
                published.computedAt(),
                items);
    }
}
