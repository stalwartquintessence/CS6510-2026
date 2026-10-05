package com.supermarket.analytics;

import com.supermarket.analytics.pipeline.Pipe;
import com.supermarket.analytics.pipeline.Stage;
import com.supermarket.persistence.ItemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Assembles and runs the popular-items pipeline:
 *
 * <pre>
 *   recordScan ─▶ [scans] ─▶ Window ─▶ [snapshots] ─▶ Rank ─▶ [rankings] ─▶ Enrich ─▶ [rows] ─▶ Publish
 *                ingress                conflating             bounded               bounded
 * </pre>
 *
 * <p>Pipe choices, from the left:
 * <ul>
 *   <li><b>scans</b> — request threads {@code offer} and never block. Sized so
 *       it only fills if a stage thread has died.</li>
 *   <li><b>snapshots</b> — conflating. Each snapshot is a complete window, so if
 *       the stages downstream are slow (a slow DB write), an unconsumed snapshot
 *       is simply replaced by the newer one. Window therefore never blocks, and
 *       back-pressure can never reach the scan pipe and start losing raw scans.
 *       This is the pipeline form of week 2's "tryLock, skip if busy".</li>
 *   <li><b>rankings</b>, <b>rows</b> — ordinary bounded pipes with blocking
 *       hand-off; at two messages a second they are effectively never full.</li>
 * </ul>
 *
 * <p>Lifecycle: phase 0 starts the stages before the embedded web server and
 * stops them after it, so no scan arrives at a pipeline that is not running.
 * {@link #stop()} lets each stage finish its current message and joins it, so
 * nothing is mid-transaction when the DataSource closes.
 */
@Component
class ScanAnalyticsPipeline implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScanAnalyticsPipeline.class);

    /** How many ranked rows to keep, so varying ?limit values can be served. */
    static final int RANKING_DEPTH = 50;
    private static final int SCAN_PIPE_CAPACITY = 65_536;
    private static final int STAGE_PIPE_CAPACITY = 4;
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);

    private final Pipe<String> scans;
    private final Pipe<WindowSnapshot> snapshots;
    private final PublishSink publish;
    private final List<Stage<?>> stages;
    private volatile boolean running;

    ScanAnalyticsPipeline(AnalyticsProperties properties,
                          ItemDao itemDao,
                          PopularItemsRankingWriter rankingWriter) {
        this.scans = Pipe.ingress(SCAN_PIPE_CAPACITY);
        this.snapshots = Pipe.conflating();
        Pipe<Ranking> rankings = Pipe.bounded(STAGE_PIPE_CAPACITY);
        Pipe<EnrichedRanking> rows = Pipe.bounded(STAGE_PIPE_CAPACITY);

        this.publish = new PublishSink(rows, rankingWriter);
        this.stages = List.of(
                new WindowFilter(scans, snapshots, properties.windowSize(), properties.slideInterval()),
                new RankFilter(snapshots, rankings, RANKING_DEPTH),
                new EnrichFilter(rankings, rows, itemDao),
                publish);
    }

    /**
     * Feed one scanned SKU into the pipeline. Never blocks the calling request
     * thread.
     *
     * @return false if the scan was dropped because the scan pipe was full
     */
    boolean submit(String sku) {
        return scans.offer(sku);
    }

    /** The most recently published ranking, or {@code null} before the first one. */
    PublishedRanking latest() {
        return publish.latest();
    }

    /** Raw scans rejected because the scan pipe was full. Expected to stay 0. */
    long droppedScans() {
        return scans.discarded();
    }

    /** Window snapshots replaced by a newer one before Rank took them. */
    long supersededSnapshots() {
        return snapshots.discarded();
    }

    @Override
    public void start() {
        stages.forEach(Stage::start);
        running = true;
        log.info("Analytics pipeline started: {}", stages.stream().map(Stage::name).toList());
    }

    @Override
    public void stop() {
        stages.forEach(Stage::requestStop);
        for (Stage<?> stage : stages) {
            try {
                if (!stage.awaitStop(STOP_TIMEOUT)) {
                    log.warn("Pipeline stage '{}' did not stop within {}; interrupted", stage.name(), STOP_TIMEOUT);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        running = false;
        for (Stage<?> stage : stages) {
            log.info("Pipeline stage '{}': {} handled, {} failed", stage.name(), stage.handled(), stage.failures());
        }
        log.info("Analytics pipeline stopped: {} scans dropped, {} snapshots superseded",
                droppedScans(), supersededSnapshots());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
