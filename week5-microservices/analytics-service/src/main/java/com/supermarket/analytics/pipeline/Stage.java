package com.supermarket.analytics.pipeline;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One step of a pipeline: a dedicated thread that takes messages from its input
 * pipe and handles them one at a time.
 *
 * <p>Because exactly one thread ever runs a stage, a stage's own fields need no
 * synchronization at all — the pipes are the only shared state in the system.
 *
 * <p>The loop is built to survive: a {@link RuntimeException} while handling one
 * message is logged and counted, and the stage moves on to the next message
 * rather than dying silently. Shutdown is cooperative — {@link #requestStop()}
 * clears a flag the loop checks between polls — so a stage is never interrupted
 * in the middle of a database call unless it fails to finish in time.
 *
 * <p>Deliberately free of Spring and of any application type, so the framework
 * can be read (and enforced, see {@code PipelineArchitectureTest}) as a generic
 * pipes-and-filters mechanism.
 */
public abstract class Stage<I> implements Runnable {

    private static final Logger log = System.getLogger(Stage.class.getName());
    private static final long POLL_MILLIS = 100;

    private final String name;
    private final Pipe<I> input;
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private volatile boolean running;
    private Thread thread;

    protected Stage(String name, Pipe<I> input) {
        this.name = name;
        this.input = input;
    }

    /** Handle one message from the input pipe. */
    protected abstract void handle(I item) throws InterruptedException;

    @Override
    public final void run() {
        try {
            while (running) {
                I item = input.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (item == null) {
                    continue;
                }
                try {
                    handle(item);
                    handled.incrementAndGet();
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                    log.log(Level.WARNING, "Pipeline stage '" + name + "' failed on one message; continuing", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.log(Level.ERROR, "Pipeline stage '" + name + "' died", t);
        }
    }

    /** Start this stage on its own daemon thread, named {@code pipeline-<name>}. */
    public final synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("Stage '" + name + "' already started");
        }
        running = true;
        thread = new Thread(this, "pipeline-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Ask the loop to exit after the message it is currently handling. Returns immediately. */
    public final void requestStop() {
        running = false;
    }

    /**
     * Wait for the loop to exit; interrupt it only if it has not finished within
     * {@code timeout}.
     *
     * @return true if the thread exited cleanly within the timeout
     */
    public final boolean awaitStop(Duration timeout) throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t == null) {
            return true;
        }
        t.join(timeout.toMillis());
        if (!t.isAlive()) {
            return true;
        }
        t.interrupt();
        t.join(timeout.toMillis());
        return false;
    }

    public final String name() {
        return name;
    }

    public final long handled() {
        return handled.get();
    }

    public final long failures() {
        return failures.get();
    }
}
