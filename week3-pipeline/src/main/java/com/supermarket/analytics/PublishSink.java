package com.supermarket.analytics;

import com.supermarket.analytics.pipeline.Pipe;
import com.supermarket.analytics.pipeline.Sink;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stage 4 — makes a finished ranking visible.
 *
 * <p>First rewrites the {@code popular_items} table in its own transaction, so
 * the ranking is durable and queryable after the fact. Then, only once that has
 * committed, swaps in an immutable {@link PublishedRanking} that
 * {@code GET /analytics/popular-items} serves from.
 *
 * <p>Week 2 rewrote the table inside whichever customer's scan crossed the slide
 * boundary, and two rewrites could overlap because the recompute lock was
 * released before the scan's transaction committed. With one sink thread,
 * rewrites are strictly sequential and no customer request ever waits on one.
 */
class PublishSink extends Sink<EnrichedRanking> {

    private final PopularItemsRankingWriter rankingWriter;
    private final AtomicReference<PublishedRanking> latest = new AtomicReference<>();

    PublishSink(Pipe<EnrichedRanking> input, PopularItemsRankingWriter rankingWriter) {
        super("publish", input);
        this.rankingWriter = rankingWriter;
    }

    @Override
    protected void consume(EnrichedRanking ranking) {
        rankingWriter.rewrite(ranking.rows());
        latest.set(new PublishedRanking(
                ranking.windowStart(), ranking.windowEnd(), Instant.now(), ranking.rows()));
    }

    /** The most recently committed ranking, or {@code null} before the first one. */
    PublishedRanking latest() {
        return latest.get();
    }
}
