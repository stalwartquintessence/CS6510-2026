package com.supermarket.analytics.pipeline;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded, thread-safe connection between two stages. Filters never hold a
 * reference to each other — only to the pipe they read from and the pipe they
 * write to — so any stage can be replaced or re-ordered without the others
 * knowing.
 *
 * <p>Three flavours, chosen per connection by what the downstream data means:
 * <ul>
 *   <li>{@link #ingress} — a {@link LinkedBlockingQueue}. Producers and the
 *       consumer lock separately, which matters when up to 200 request threads
 *       offer into the same pipe.</li>
 *   <li>{@link #bounded} — an {@link ArrayBlockingQueue}; {@link #put} blocks
 *       when full, giving ordinary back-pressure between internal stages.</li>
 *   <li>{@link #conflating} — capacity one, latest wins. For messages that are
 *       complete snapshots, where an older one is worthless once a newer one
 *       exists, so the producer must never wait for a slow consumer.</li>
 * </ul>
 */
public final class Pipe<T> {

    private final BlockingQueue<T> queue;
    private final boolean conflating;
    private final AtomicLong discarded = new AtomicLong();

    private Pipe(BlockingQueue<T> queue, boolean conflating) {
        this.queue = queue;
        this.conflating = conflating;
    }

    /** Entry pipe for many concurrent producers. */
    public static <T> Pipe<T> ingress(int capacity) {
        return new Pipe<>(new LinkedBlockingQueue<>(capacity), false);
    }

    /** Internal pipe with blocking back-pressure. */
    public static <T> Pipe<T> bounded(int capacity) {
        return new Pipe<>(new ArrayBlockingQueue<>(capacity), false);
    }

    /** Single-slot pipe whose newest message replaces any unconsumed one. */
    public static <T> Pipe<T> conflating() {
        return new Pipe<>(new ArrayBlockingQueue<>(1), true);
    }

    /**
     * Hand a message downstream. Blocks while a bounded pipe is full; a
     * conflating pipe never blocks, it evicts the stale message instead.
     */
    public void put(T item) throws InterruptedException {
        if (conflating) {
            replace(item);
        } else {
            queue.put(item);
        }
    }

    /**
     * Non-blocking hand-off for callers that must never wait (request threads).
     * On a full bounded pipe the message is rejected and counted; a conflating
     * pipe evicts the stale message and always accepts.
     *
     * @return true if the message was accepted
     */
    public boolean offer(T item) {
        if (conflating) {
            replace(item);
            return true;
        }
        if (queue.offer(item)) {
            return true;
        }
        discarded.incrementAndGet();
        return false;
    }

    /** Next message, or {@code null} if none arrives within the timeout. */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * Messages that never reached the consumer: rejected because the pipe was
     * full, or (for a conflating pipe) superseded before they were taken.
     */
    public long discarded() {
        return discarded.get();
    }

    public int size() {
        return queue.size();
    }

    private void replace(T item) {
        // Safe with a single producer: if the consumer takes the stale message
        // between our failed offer and our poll, the poll just finds nothing.
        while (!queue.offer(item)) {
            if (queue.poll() != null) {
                discarded.incrementAndGet();
            }
        }
    }
}
