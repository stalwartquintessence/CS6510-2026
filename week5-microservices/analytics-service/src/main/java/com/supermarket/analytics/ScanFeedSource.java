package com.supermarket.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The analytics service's input: pages through the transaction service's scan feed in
 * id order and feeds each scanned SKU into the pipeline's scan pipe.
 *
 * <p>Week 4's {@code ScanLogSource} did the same against the {@code scan_log} table in
 * the shared database. In week 5 that table is the transaction service's private outbox
 * ({@code scan_events}), written in the same local transaction as the basket, and this
 * service sees it only through the {@code ScanFeed} RPC. The cursor is ours, like a
 * consumer offset: the producer neither knows nor cares how far we have read, so either
 * side can be down without the other noticing, and we resume where we left off.
 *
 * <h2>The settle delay</h2>
 * The ids come from a database sequence, and sequence order is not commit order: scan
 * 41 can commit while scan 40's transaction is still open. A reader that has moved its
 * cursor past 41 would then never see 40. To close that gap an event is read only once
 * it is {@code settleDelay} old (250 ms by default, vastly longer than a scan's
 * few-millisecond transaction), and reading stops at the first event that is too new,
 * so order is preserved. The price is that the ranking lags real time by about that
 * long, which is invisible next to a window that hops every 500 scans. The timestamps
 * are the transaction service's clock compared with ours; on one machine they are the
 * same clock.
 *
 * <p>The window's {@code windowStart}/{@code windowEnd} remain the pipeline's own
 * sequence numbers, counting scans since this service started reading (plus the replay
 * below), exactly as they counted since JVM start in week 3.
 */
@Component
class ScanFeedSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScanFeedSource.class);
    private static final int BATCH_SIZE = 2_000;
    private static final long RETRY_MILLIS = 1_000;

    private final ScanFeed feed;
    private final ScanAnalyticsPipeline pipeline;
    private final ScanSourceProperties properties;
    private final int replay;
    private final Clock clock;

    private long cursor;
    private long submitted;
    private volatile boolean running;
    private Thread thread;

    @Autowired
    ScanFeedSource(ScanFeed feed,
                   ScanAnalyticsPipeline pipeline,
                   ScanSourceProperties properties,
                   AnalyticsProperties analytics) {
        this(feed, pipeline, properties, analytics.windowSize(), Clock.systemUTC());
    }

    ScanFeedSource(ScanFeed feed,
                   ScanAnalyticsPipeline pipeline,
                   ScanSourceProperties properties,
                   int replay,
                   Clock clock) {
        this.feed = feed;
        this.pipeline = pipeline;
        this.properties = properties;
        this.replay = replay;
        this.clock = clock;
    }

    /**
     * Reads everything that has settled since the cursor and submits it.
     *
     * @return how many events were submitted; a full batch means more is waiting
     */
    int pollOnce() {
        Instant settled = clock.instant().minus(properties.settleDelay());
        List<ScanEvent> events = feed.readAfter(cursor, BATCH_SIZE);
        int consumed = 0;
        for (ScanEvent event : events) {
            if (event.scannedAt().isAfter(settled)) {
                break;
            }
            if (!pipeline.submit(event.sku())) {
                log.warn("Scan pipe full; scan {} dropped", event.id());
            }
            cursor = event.id();
            consumed++;
        }
        submitted += consumed;
        return consumed;
    }

    /** Where to resume after a restart: far enough back to refill one window, never before the start of the feed. */
    void positionCursor() {
        cursor = Math.max(0, feed.latestId() - replay);
    }

    long submitted() {
        return submitted;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::run, "scan-feed-source");
        thread.start();
    }

    private void run() {
        // The transaction service may be down when we start; that must not stop us starting.
        while (running) {
            try {
                positionCursor();
                log.info("Reading the scan feed from id {}", cursor);
                break;
            } catch (RuntimeException e) {
                log.warn("Scan feed not reachable yet ({}); retrying", e.toString());
                if (!pause(RETRY_MILLIS)) {
                    return;
                }
            }
        }
        while (running) {
            try {
                if (pollOnce() < BATCH_SIZE && !pause(properties.pollInterval().toMillis())) {
                    return;
                }
            } catch (RuntimeException e) {
                // The producer being down must not kill the source; the cursor has not moved, so the next poll retries.
                log.warn("Scan feed poll failed: {}", e.toString());
                if (!pause(RETRY_MILLIS)) {
                    return;
                }
            }
        }
    }

    /** @return false if interrupted, i.e. told to stop */
    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Scan feed source stopped after {} scans (cursor at id {})", submitted, cursor);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** After the pipeline (phase 0): started once the stages are up, stopped before they are. */
    @Override
    public int getPhase() {
        return 1;
    }
}
