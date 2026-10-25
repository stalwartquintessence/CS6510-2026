package com.supermarket.analytics;

import com.supermarket.analytics.pipeline.Filter;
import com.supermarket.analytics.pipeline.Pipe;

import java.util.ArrayDeque;

/**
 * Stage 1 — the hopping window itself.
 *
 * <p>Consumes every scanned SKU in arrival order, numbers it with the global
 * scan sequence, keeps the most recent {@code windowSize} of them, and every
 * {@code slideInterval} scans emits a {@link WindowSnapshot} downstream.
 *
 * <p>Week 2 needed a {@code ConcurrentLinkedQueue}, an {@code AtomicInteger}
 * size counter, an {@code AtomicLong} sequence and a {@code ReentrantLock}
 * because up to 200 request threads mutated the window concurrently. Here one
 * thread owns it, so it is a plain {@link ArrayDeque} and a {@code long}.
 * Arrival order through the scan pipe <em>is</em> the global order, so no
 * slide boundary can be skipped or double-counted.
 */
class WindowFilter extends Filter<String, WindowSnapshot> {

    private final int windowSize;
    private final int slideInterval;
    private final ArrayDeque<String> window;
    private long seq;

    WindowFilter(Pipe<String> input, Pipe<WindowSnapshot> output, int windowSize, int slideInterval) {
        super("window", input, output);
        this.windowSize = windowSize;
        this.slideInterval = slideInterval;
        this.window = new ArrayDeque<>(windowSize + 1);
    }

    @Override
    protected WindowSnapshot process(String sku) {
        window.addLast(sku);
        if (window.size() > windowSize) {
            window.removeFirst();
        }
        seq++;
        if (seq % slideInterval != 0) {
            return null;
        }
        return new WindowSnapshot(Math.max(0, seq - windowSize), seq, window.stream().toList());
    }
}
