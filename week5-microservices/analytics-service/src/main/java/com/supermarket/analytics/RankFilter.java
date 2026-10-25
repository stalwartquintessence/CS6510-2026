package com.supermarket.analytics;

import com.supermarket.analytics.pipeline.Filter;
import com.supermarket.analytics.pipeline.Pipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 — counts the SKUs in one window and ranks them: scan count
 * descending, SKU ascending to break ties (the same order as weeks 1 and 2),
 * truncated to the top {@code depth}.
 *
 * <p>A pure function of its input: no I/O, no state carried between windows.
 */
class RankFilter extends Filter<WindowSnapshot, Ranking> {

    private final int depth;

    RankFilter(Pipe<WindowSnapshot> input, Pipe<Ranking> output, int depth) {
        super("rank", input, output);
        this.depth = depth;
    }

    @Override
    protected Ranking process(WindowSnapshot snapshot) {
        Map<String, Integer> counts = new HashMap<>();
        for (String sku : snapshot.skus()) {
            counts.merge(sku, 1, Integer::sum);
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator
                .comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));

        int n = Math.min(depth, sorted.size());
        List<RankedSku> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            entries.add(new RankedSku(e.getKey(), e.getValue(), i + 1));
        }
        return new Ranking(snapshot.windowStart(), snapshot.windowEnd(), entries);
    }
}
