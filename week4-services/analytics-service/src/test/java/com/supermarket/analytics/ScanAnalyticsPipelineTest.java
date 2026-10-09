package com.supermarket.analytics;

import com.supermarket.analytics.model.PopularItemsView;
import com.supermarket.domain.ItemSnapshot;
import com.supermarket.domain.PopularItemRow;
import com.supermarket.persistence.ItemDao;
import com.supermarket.persistence.PopularItemsDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drives the real pipeline — real threads, real pipes — against in-memory fakes
 * of the two DAOs. No Spring context and no database.
 */
class ScanAnalyticsPipelineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private FakeItemDao itemDao;
    private FakePopularItemsDao popularItemsDao;
    private ScanAnalyticsPipeline pipeline;

    @BeforeEach
    void startPipeline() {
        itemDao = new FakeItemDao();
        popularItemsDao = new FakePopularItemsDao();
        pipeline = new ScanAnalyticsPipeline(
                new AnalyticsProperties(1000, 500), itemDao, new PopularItemsRankingWriter(popularItemsDao));
        pipeline.start();
    }

    @AfterEach
    void stopPipeline() {
        pipeline.stop();
    }

    @Test
    void publishesTheFirstFullWindowWithAuditableBounds() {
        scan("SKU-A", 300);
        scan("SKU-B", 200);
        scan("SKU-C", 100);
        for (int i = 0; i < 40; i++) {
            scan(String.format("SKU-X%02d", i), 10);
        }

        PublishedRanking published = awaitWindowEnd(1000);

        assertEquals(0, published.windowStart());
        assertEquals(1000, published.windowEnd());
        List<PopularItemRow> rows = published.rows();
        assertEquals(new PopularItemRow("SKU-A", "Name of SKU-A", 300, 1), rows.get(0));
        assertEquals(new PopularItemRow("SKU-B", "Name of SKU-B", 200, 2), rows.get(1));
        assertEquals(new PopularItemRow("SKU-C", "Name of SKU-C", 100, 3), rows.get(2));
        // 40 SKUs tied at 10 scans each break ties by SKU, ascending.
        assertEquals(new PopularItemRow("SKU-X00", "Name of SKU-X00", 10, 4), rows.get(3));
        assertEquals(43, rows.size());
        // The sink persisted exactly what it published.
        assertEquals(rows, popularItemsDao.stored);
        assertEquals(0, pipeline.droppedScans());
    }

    @Test
    void theWindowHopsAndForgetsScansOlderThanWindowSize() {
        scan("SKU-OLD", 1500);
        scan("SKU-NEW", 1000);

        PublishedRanking published = awaitWindowEnd(2500);

        assertEquals(1500, published.windowStart());
        assertEquals(List.of(new PopularItemRow("SKU-NEW", "Name of SKU-NEW", 1000, 1)), published.rows());
    }

    @Test
    void aSlowSinkSupersedesSnapshotsInsteadOfDroppingScans() {
        popularItemsDao.delayMillis = 50;
        for (int i = 0; i < 20_000; i++) {
            scan("SKU-" + (i % 97), 1);
        }

        PublishedRanking published = awaitWindowEnd(20_000);

        assertEquals(19_000, published.windowStart());
        assertEquals(0, pipeline.droppedScans(), "back-pressure must never reach the scan pipe");
        assertTrue(pipeline.supersededSnapshots() > 0, "stale snapshots should have been conflated away");
        assertTrue(popularItemsDao.rewrites.get() < 40, "fewer rewrites than slide boundaries");
    }

    @Test
    void aFailedLookupSkipsOneWindowButTheStageKeepsRunning() {
        itemDao.failNext = true;
        scan("SKU-A", 500);
        await(() -> itemDao.lookups.get() >= 1, "first lookup attempted");

        scan("SKU-A", 500);
        PublishedRanking published = awaitWindowEnd(1000);

        assertEquals(List.of(new PopularItemRow("SKU-A", "Name of SKU-A", 1000, 1)), published.rows());
        assertEquals(1, popularItemsDao.rewrites.get(), "the window that failed in Enrich was never written");
    }

    @Test
    void serviceFallsBackToTheTableBeforeTheFirstPublishThenServesThePipeline() {
        DefaultAnalyticsService service =
                new DefaultAnalyticsService(pipeline, popularItemsDao, new AnalyticsProperties(1000, 500));
        popularItemsDao.stored = List.of(new PopularItemRow("SKU-PREV", "From before restart", 7, 1));

        PopularItemsView before = service.getPopular(10);
        assertNull(pipeline.latest());
        assertEquals(0, before.windowStart());
        assertEquals(0, before.windowEnd());
        assertEquals("SKU-PREV", before.items().get(0).sku());

        for (int i = 0; i < 500; i++) {
            pipeline.submit("SKU-" + (i % 20));
        }
        awaitWindowEnd(500);

        PopularItemsView after = service.getPopular(3);
        assertEquals(0, after.windowStart());
        assertEquals(500, after.windowEnd());
        assertEquals(3, after.items().size());
        assertEquals(1000, after.windowSize());
        assertEquals(500, after.slideInterval());
    }

    private void scan(String sku, int times) {
        for (int i = 0; i < times; i++) {
            assertTrue(pipeline.submit(sku), "scan pipe rejected a scan");
        }
    }

    private PublishedRanking awaitWindowEnd(long windowEnd) {
        await(() -> pipeline.latest() != null && pipeline.latest().windowEnd() == windowEnd,
                "a published ranking ending at scan " + windowEnd);
        return pipeline.latest();
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted waiting for " + what);
            }
        }
    }

    private static final class FakeItemDao implements ItemDao {

        final AtomicInteger lookups = new AtomicInteger();
        volatile boolean failNext;

        @Override
        public Optional<ItemSnapshot> findBySku(String sku) {
            lookups.incrementAndGet();
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("simulated database outage");
            }
            return Optional.of(new ItemSnapshot(sku, "Name of " + sku, 1.0, 100));
        }

        @Override
        public List<ItemSnapshot> findAllOrderBySku() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ItemSnapshot> findBelowStock(int threshold) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void decrementStockForUpdate(String sku, int quantity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveAll(List<ItemSnapshot> items) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakePopularItemsDao implements PopularItemsDao {

        final AtomicInteger rewrites = new AtomicInteger();
        volatile List<PopularItemRow> stored = List.of();
        volatile long delayMillis;

        @Override
        public List<PopularItemRow> findRanking() {
            return stored;
        }

        @Override
        public void replaceRanking(List<PopularItemRow> rows) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            stored = List.copyOf(rows);
            rewrites.incrementAndGet();
        }
    }
}
