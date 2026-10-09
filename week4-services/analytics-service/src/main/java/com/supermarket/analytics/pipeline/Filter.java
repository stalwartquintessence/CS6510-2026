package com.supermarket.analytics.pipeline;

/**
 * A stage that transforms each input message into at most one output message
 * and passes it to the next pipe.
 *
 * <p>{@link #process} may return {@code null} to mean "nothing to forward for
 * this input" — e.g. a windowing filter that consumes every scan but only
 * emits once per slide interval.
 */
public abstract class Filter<I, O> extends Stage<I> {

    private final Pipe<O> output;

    protected Filter(String name, Pipe<I> input, Pipe<O> output) {
        super(name, input);
        this.output = output;
    }

    /** Transform one message; {@code null} forwards nothing. */
    protected abstract O process(I item);

    @Override
    protected final void handle(I item) throws InterruptedException {
        O result = process(item);
        if (result != null) {
            output.put(result);
        }
    }
}
