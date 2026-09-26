package com.supermarket.analytics;

import com.supermarket.analytics.model.PopularItemView;
import com.supermarket.analytics.model.PopularItemsView;
import com.supermarket.domain.ItemSnapshot;
import com.supermarket.domain.PopularItemRow;
import com.supermarket.persistence.ItemDao;
import com.supermarket.persistence.PopularItemsDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hopping-window popularity: the last {@code windowSize} scans, recomputed
 * every {@code slideInterval} scans. The ranking is persisted so varying
 * {@code ?limit} values can be served from one computation, and the window
 * bounds are reported alongside it so clients can audit what it covers.
 */
@Service
class DefaultAnalyticsService implements AnalyticsService {

    /** How many ranked rows to persist, so varying ?limit values can be served. */
    private static final int RANKING_DEPTH = 50;

    private final ScanWindow window;
    private final PopularItemsRankingWriter rankingWriter;
    private final PopularItemsDao popularItemsDao;
    private final ItemDao itemDao;
    private final int slideInterval;

    DefaultAnalyticsService(ScanWindow window,
                            PopularItemsRankingWriter rankingWriter,
                            PopularItemsDao popularItemsDao,
                            ItemDao itemDao,
                            AnalyticsProperties properties) {
        this.window = window;
        this.rankingWriter = rankingWriter;
        this.popularItemsDao = popularItemsDao;
        this.itemDao = itemDao;
        this.slideInterval = properties.slideInterval();
    }

    @Override
    public void recordScan(String sku) {
        long seq = window.record(sku);
        if (seq % slideInterval == 0) {
            recompute(seq);
        }
    }

    private void recompute(long seq) {
        // Skip if another thread is already recomputing; the next slide
        // boundary will catch up.
        if (!window.tryClaimRecompute()) {
            return;
        }
        try {
            Map<String, Integer> counts = new HashMap<>();
            for (String sku : window.snapshot()) {
                counts.merge(sku, 1, Integer::sum);
            }

            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
            ranked.sort(Comparator
                    .comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                    .thenComparing(Map.Entry::getKey));

            int depth = Math.min(RANKING_DEPTH, ranked.size());
            List<PopularItemRow> rows = new ArrayList<>(depth);
            for (int i = 0; i < depth; i++) {
                Map.Entry<String, Integer> entry = ranked.get(i);
                String name = itemDao.findBySku(entry.getKey())
                        .map(ItemSnapshot::name)
                        .orElse(entry.getKey());
                rows.add(new PopularItemRow(entry.getKey(), name, entry.getValue(), i + 1));
            }

            rankingWriter.rewrite(rows);
            window.publishBounds(seq);
        } finally {
            window.releaseRecompute();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PopularItemsView getPopular(int limit) {
        int effectiveLimit = Math.max(0, limit);
        List<PopularItemRow> ranked = popularItemsDao.findRanking();
        List<PopularItemView> items = new ArrayList<>(Math.min(effectiveLimit, ranked.size()));
        for (PopularItemRow row : ranked) {
            if (items.size() >= effectiveLimit) {
                break;
            }
            items.add(new PopularItemView(row.sku(), row.name(), row.scanCount(), row.rank()));
        }
        Instant computedAt = window.computedAt() != null ? window.computedAt() : Instant.now();
        return new PopularItemsView(
                window.windowSize(),
                slideInterval,
                window.windowStart(),
                window.windowEnd(),
                computedAt,
                items);
    }
}
