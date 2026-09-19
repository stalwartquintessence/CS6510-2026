package com.supermarket.service;

import com.supermarket.dto.Dtos.PopularItemDto;
import com.supermarket.dto.Dtos.PopularItemsResponse;
import com.supermarket.model.InventoryItem;
import com.supermarket.model.PopularItem;
import com.supermarket.repository.InventoryRepository;
import com.supermarket.repository.PopularItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks the most-scanned items over a hopping window: the last
 * {@code windowSize} scans, recomputed every {@code slideInterval} scans. Each
 * scan is appended to an in-memory {@link ConcurrentLinkedQueue}; when the
 * global scan counter crosses a slide boundary the top-N ranking is recomputed
 * and persisted to the {@code popular_items} table, along with the global
 * sequence numbers ({@code windowStart}/{@code windowEnd}) that bound it.
 */
@Service
public class AnalyticsService {

    /** How many ranked rows to persist, so varying ?limit values can be served. */
    private static final int RANKING_DEPTH = 50;

    private final PopularItemRepository popularItemRepository;
    private final InventoryRepository inventoryRepository;
    private final int windowSize;
    private final int slideInterval;

    private final ConcurrentLinkedQueue<String> recentScans = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong globalScanSeq = new AtomicLong();
    private final ReentrantLock recomputeLock = new ReentrantLock();

    // Metadata of the last computed window (global scan sequence numbers).
    private volatile long windowStart = 0;
    private volatile long windowEnd = 0;
    private volatile Instant computedAt = null;

    public AnalyticsService(PopularItemRepository popularItemRepository,
                            InventoryRepository inventoryRepository,
                            @Value("${supermarket.analytics.window-size:1000}") int windowSize,
                            @Value("${supermarket.analytics.slide-interval:500}") int slideInterval) {
        this.popularItemRepository = popularItemRepository;
        this.inventoryRepository = inventoryRepository;
        this.windowSize = windowSize;
        this.slideInterval = slideInterval;
    }

    /**
     * Record one scanned unit. Appends to the sliding window, trims it back to
     * {@code windowSize}, and triggers a recompute on every slide boundary.
     * Runs within the caller's scan transaction, so the persisted ranking is
     * committed atomically with the scan that triggered it.
     */
    public void recordScan(String sku) {
        recentScans.add(sku);
        long seq = globalScanSeq.incrementAndGet();

        // Trim the window back down to windowSize. Using a counter avoids the
        // O(n) cost of ConcurrentLinkedQueue.size() on every scan.
        int size = queueSize.incrementAndGet();
        while (size > windowSize) {
            if (recentScans.poll() != null) {
                size = queueSize.decrementAndGet();
            } else {
                break;
            }
        }

        if (seq % slideInterval == 0) {
            recompute(seq);
        }
    }

    private void recompute(long seq) {
        // Only one recompute at a time; skip if another thread is already on it
        // (the next slide boundary will catch up).
        if (!recomputeLock.tryLock()) {
            return;
        }
        try {
            List<String> snapshot = new ArrayList<>(recentScans);
            Map<String, Integer> counts = new HashMap<>();
            for (String sku : snapshot) {
                counts.merge(sku, 1, Integer::sum);
            }

            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
            ranked.sort(Comparator
                    .comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                    .thenComparing(Map.Entry::getKey));

            int depth = Math.min(RANKING_DEPTH, ranked.size());
            List<PopularItem> rows = new ArrayList<>(depth);
            for (int i = 0; i < depth; i++) {
                Map.Entry<String, Integer> e = ranked.get(i);
                String name = inventoryRepository.findById(e.getKey())
                        .map(InventoryItem::getName)
                        .orElse(e.getKey());
                rows.add(new PopularItem(e.getKey(), name, e.getValue(), i + 1));
            }

            persistRanking(rows);

            this.windowStart = Math.max(0, seq - windowSize);
            this.windowEnd = seq;
            this.computedAt = Instant.now();
        } finally {
            recomputeLock.unlock();
        }
    }

    /** Atomically replace the persisted ranking (joins the caller's transaction). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void persistRanking(List<PopularItem> rows) {
        popularItemRepository.deleteAllInBatch();
        popularItemRepository.saveAll(rows);
    }

    @Transactional(readOnly = true)
    public PopularItemsResponse getPopular(int limit) {
        List<PopularItem> ranked = popularItemRepository.findAllByOrderByRankAsc();
        List<PopularItemDto> items = new ArrayList<>(Math.min(limit, ranked.size()));
        for (PopularItem p : ranked) {
            if (items.size() >= limit) {
                break;
            }
            items.add(new PopularItemDto(p.getSku(), p.getName(), p.getScanCount(), p.getRank()));
        }
        String computed = computedAt != null ? computedAt.toString() : Instant.now().toString();
        return new PopularItemsResponse(windowSize, slideInterval, windowStart, windowEnd, computed, items);
    }
}
