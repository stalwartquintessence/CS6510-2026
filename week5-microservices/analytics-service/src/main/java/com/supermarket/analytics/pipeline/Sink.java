package com.supermarket.analytics.pipeline;

/** The terminal stage of a pipeline: consumes messages and forwards nothing. */
public abstract class Sink<I> extends Stage<I> {

    protected Sink(String name, Pipe<I> input) {
        super(name, input);
    }

    /** Consume one message, typically by writing it somewhere outside the pipeline. */
    protected abstract void consume(I item);

    @Override
    protected final void handle(I item) {
        consume(item);
    }
}
