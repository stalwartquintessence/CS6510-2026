package com.supermarket.analytics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The in-memory hopping window: the most recent {@code windowSize} scanned
 * SKUs, a global scan sequence, and the bounds of the last computed ranking.
 *
 * <p>Week 1 kept all of this as fields on the analytics service itself, which
 * left no seam between "what the window is" and "what we do with it". Pulling
 * it into its own bean means the recompute policy can be read (and later
 * replaced) without touching the state machine.
 *
 * <p>State is per-JVM and resets on restart — an accepted limitation of keeping
 * the window in process rather than in the database.
 */
@Component
class ScanWindow {

    private final int windowSize;

    private final ConcurrentLinkedQueue<String> recentScans = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong globalScanSeq = new AtomicLong();
    private final ReentrantLock recomputeLock = new ReentrantLock();

    // Bounds of the last computed window, as global scan sequence numbers.
    private volatile long windowStart = 0;
    private volatile long windowEnd = 0;
    private volatile Instant computedAt = null;

    ScanWindow(AnalyticsProperties properties) {
        this.windowSize = properties.windowSize();
    }

    /**
     * Append one scan and trim back to {@code windowSize}.
     *
     * @return the new global scan sequence number
     */
    long record(String sku) {
        recentScans.add(sku);
        long seq = globalScanSeq.incrementAndGet();

        // Using a counter avoids the O(n) cost of ConcurrentLinkedQueue.size()
        // on every scan.
        int size = queueSize.incrementAndGet();
        while (size > windowSize) {
            if (recentScans.poll() != null) {
                size = queueSize.decrementAndGet();
            } else {
                break;
            }
        }
        return seq;
    }

    /**
     * Try to claim the right to recompute. Non-blocking on purpose: a scan that
     * arrives mid-recompute must never stall behind it — the next slide
     * boundary will catch up.
     *
     * @return true if the caller now holds the lock and must call {@link #releaseRecompute}
     */
    boolean tryClaimRecompute() {
        return recomputeLock.tryLock();
    }

    void releaseRecompute() {
        recomputeLock.unlock();
    }

    /** Snapshot of the SKUs currently in the window. */
    List<String> snapshot() {
        return new ArrayList<>(recentScans);
    }

    void publishBounds(long seq) {
        this.windowStart = Math.max(0, seq - windowSize);
        this.windowEnd = seq;
        this.computedAt = Instant.now();
    }

    int windowSize() {
        return windowSize;
    }

    long windowStart() {
        return windowStart;
    }

    long windowEnd() {
        return windowEnd;
    }

    Instant computedAt() {
        return computedAt;
    }
}
