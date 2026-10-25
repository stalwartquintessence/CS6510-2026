package com.supermarket.analytics;

import com.supermarket.analytics.pipeline.Filter;
import com.supermarket.analytics.pipeline.Pipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 3 — attaches catalog names to a ranking.
 *
 * <p>Week 2 issued up to 50 {@code findBySku} queries on every recompute, on a
 * customer's request thread. Item names never change, so this filter keeps a
 * private SKU→name cache: after the first few windows the hot (Zipf-ranked)
 * SKUs are all cached and the stage does no I/O at all. The cache needs no
 * synchronization because only this stage's thread touches it.
 *
 * <p>Week 5: a miss is now a call to the catalog service instead of a query on the
 * shared {@code items} table. The cache matters more for that — it is what keeps the
 * ranking working, after the first windows, with the catalog service down.
 *
 * <p>Only successful lookups are cached. An unknown SKU falls back to the SKU
 * itself as its name but is looked up again next time (the catalog may still
 * be seeding), and a lookup that throws fails the whole message, which
 * {@code Stage} logs and skips — the next window will retry.
 */
class EnrichFilter extends Filter<Ranking, EnrichedRanking> {

    private final CatalogNames catalog;
    private final Map<String, String> names = new HashMap<>();

    EnrichFilter(Pipe<Ranking> input, Pipe<EnrichedRanking> output, CatalogNames catalog) {
        super("enrich", input, output);
        this.catalog = catalog;
    }

    @Override
    protected EnrichedRanking process(Ranking ranking) {
        List<PopularItemRow> rows = new ArrayList<>(ranking.entries().size());
        for (RankedSku entry : ranking.entries()) {
            rows.add(new PopularItemRow(entry.sku(), nameOf(entry.sku()), entry.scanCount(), entry.rank()));
        }
        return new EnrichedRanking(ranking.windowStart(), ranking.windowEnd(), rows);
    }

    private String nameOf(String sku) {
        String cached = names.get(sku);
        if (cached != null) {
            return cached;
        }
        Optional<String> found = catalog.nameOf(sku);
        found.ifPresent(name -> names.put(sku, name));
        return found.orElse(sku);
    }
}
