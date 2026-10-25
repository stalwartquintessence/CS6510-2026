package com.supermarket.analytics;

import com.supermarket.analytics.persistence.PopularItemsDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The scan feed reader, driven synchronously through {@code pollOnce()} against an
 * in-memory feed and a controllable clock. The pipeline behind it is the real one.
 * The same four cases as week 4's {@code ScanLogSourceTest}: only the source of the
 * events changed (an RPC instead of a shared table), not the rules for reading them.
 */
class ScanFeedSourceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final ScanSourceProperties PROPERTIES =
            new ScanSourceProperties(Duration.ofMillis(50), Duration.ofMillis(250));

    private final FakeScanFeed log = new FakeScanFeed();
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private ScanAnalyticsPipeline pipeline;
    private ScanFeedSource source;

    @BeforeEach
    void start() {
        pipeline = new ScanAnalyticsPipeline(
                new AnalyticsProperties(10, 5), sku -> Optional.of("Name of " + sku), new PopularItemsRankingWriter(new NoopRankingDao()));
        pipeline.start();
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        source = new ScanFeedSource(log, pipeline, PROPERTIES, 10, clock);
    }

    @AfterEach
    void stop() {
        pipeline.stop();
    }

    @Test
    void feedsSettledScansToTheWindowInIdOrder() {
        for (int i = 1; i <= 5; i++) {
            log.add("SKU-" + (i % 2), T0);
        }
        now.set(T0.plusSeconds(1));

        assertEquals(5, source.pollOnce());
        PublishedRanking ranking = awaitWindowEnd(5);
        assertEquals("SKU-1", ranking.rows().get(0).sku());   // ids 1,3,5 → three scans of SKU-1
        assertEquals(3, ranking.rows().get(0).scanCount());
        assertEquals(0, source.pollOnce());                      // cursor advanced: nothing is read twice
    }

    @Test
    void holdsBackScansYoungerThanTheSettleDelay() {
        log.add("SKU-0", T0);
        log.add("SKU-0", T0.plusMillis(900));   // too new at T0 + 1 s: its commit might still be racing a lower id
        now.set(T0.plusSeconds(1));

        assertEquals(1, source.pollOnce());
        now.set(T0.plusSeconds(2));
        assertEquals(1, source.pollOnce());
        assertEquals(2, source.submitted());
    }

    @Test
    void stopsAtTheFirstUnsettledScanSoOrderIsPreserved() {
        log.add("SKU-0", T0.plusMillis(900));   // id 1: too new
        log.add("SKU-1", T0);                    // id 2: old enough, but must wait behind id 1
        now.set(T0.plusSeconds(1));

        assertEquals(0, source.pollOnce());
        now.set(T0.plusSeconds(2));
        assertEquals(2, source.pollOnce());
    }

    @Test
    void resumesOneWindowBeforeTheEndOfAnExistingLog() {
        for (int i = 0; i < 25; i++) {
            log.add("SKU-0", T0);
        }
        now.set(T0.plusSeconds(1));

        source.positionCursor();   // replay = 10 → resume after id 15
        assertEquals(10, source.pollOnce());
    }

    private PublishedRanking awaitWindowEnd(long windowEnd) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (pipeline.latest() == null || pipeline.latest().windowEnd() != windowEnd) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for a ranking ending at scan " + windowEnd);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        assertNotNull(pipeline.latest());
        return pipeline.latest();
    }

    private static final class FakeScanFeed implements ScanFeed {

        private final List<ScanEvent> events = new ArrayList<>();

        void add(String sku, Instant at) {
            events.add(new ScanEvent(events.size() + 1L, sku, at));
        }

        @Override
        public List<ScanEvent> readAfter(long afterId, int limit) {
            return events.stream().filter(e -> e.id() > afterId).limit(limit).toList();
        }

        @Override
        public long latestId() {
            return events.size();
        }
    }

    private static final class NoopRankingDao implements PopularItemsDao {
        @Override public List<PopularItemRow> findRanking() { return List.of(); }
        @Override public void replaceRanking(List<PopularItemRow> rows) { }
    }
}
