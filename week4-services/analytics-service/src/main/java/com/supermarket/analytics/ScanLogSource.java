package com.supermarket.analytics;

import com.supermarket.domain.ScanEvent;
import com.supermarket.persistence.ScanLogDao;
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
 * The analytics service's input: tails the shared {@code scan_log} table in id order
 * and feeds each scanned SKU into the pipeline's scan pipe.
 *
 * <p>This is how analytics learns about scans <em>without the transaction service
 * ever calling it</em>. In week 3 {@code recordScan} was an in-process method call; the
 * database now carries that message, and the two services are coupled only by the
 * table's shape.
 *
 * <h2>The settle delay</h2>
 * The ids come from a database sequence, and sequence order is not commit order: scan
 * 41 can commit while scan 40's transaction is still open. A reader that has moved its
 * cursor past 41 would then never see 40. To close that gap an event is read only once
 * it is {@code settleDelay} old (250 ms by default, vastly longer than a scan's
 * few-millisecond transaction), and reading stops at the first event that is too new,
 * so order is preserved. The price is that the ranking lags real time by about that
 * long, which is invisible next to a window that hops every 500 scans.
 *
 * <p>The window's {@code windowStart}/{@code windowEnd} remain the pipeline's own
 * sequence numbers, counting scans since this service started reading (plus the replay
 * below), exactly as they counted since JVM start in week 3.
 */
@Component
class ScanLogSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScanLogSource.class);
    private static final int BATCH_SIZE = 2_000;

    private final ScanLogDao scanLog;
    private final ScanAnalyticsPipeline pipeline;
    private final ScanSourceProperties properties;
    private final int replay;
    private final Clock clock;

    private long cursor;
    private long submitted;
    private volatile boolean running;
    private Thread thread;

    @Autowired
    ScanLogSource(ScanLogDao scanLog,
                  ScanAnalyticsPipeline pipeline,
                  ScanSourceProperties properties,
                  AnalyticsProperties analytics) {
        this(scanLog, pipeline, properties, analytics.windowSize(), Clock.systemUTC());
    }

    ScanLogSource(ScanLogDao scanLog,
                  ScanAnalyticsPipeline pipeline,
                  ScanSourceProperties properties,
                  int replay,
                  Clock clock) {
        this.scanLog = scanLog;
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
        List<ScanEvent> events = scanLog.findAfter(cursor, BATCH_SIZE);
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

    /** Where to resume after a restart: far enough back to refill one window, never before the start of the log. */
    void positionCursor() {
        cursor = Math.max(0, scanLog.maxId() - replay);
    }

    long submitted() {
        return submitted;
    }

    @Override
    public void start() {
        positionCursor();
        running = true;
        thread = new Thread(this::run, "scan-log-source");
        thread.start();
        log.info("Tailing scan_log from id {}", cursor);
    }

    private void run() {
        while (running) {
            try {
                if (pollOnce() < BATCH_SIZE) {
                    Thread.sleep(properties.pollInterval().toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A database blip must not kill the source; the cursor has not moved, so the next poll retries.
                log.warn("scan_log poll failed: {}", e.toString());
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
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
        log.info("scan_log source stopped after {} scans (cursor at id {})", submitted, cursor);
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
