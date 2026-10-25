package com.supermarket.support.rpc;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A service's client side of one other service: a few plaintext HTTP/2 channels to
 * one target, handed out round-robin. The same shape as the gateway's
 * {@code ServiceClients}: one channel multiplexes many calls but pins them to one
 * Netty event loop, so a small pool is a cheap hedge.
 *
 * <p>New in week 5. In week 4 only the gateway made calls; now a service may call
 * another (along the call graph ArchUnit allows), and each such dependency is one
 * of these. Generic on purpose: the chassis knows nothing about which stubs exist.
 *
 * <h2>Reconnecting promptly</h2>
 * A channel whose server has gone away retries the connection with exponential
 * backoff (1 s, 1.6 s, 2.6 s, … up to two minutes), and fails calls fast while it
 * waits. A dead port on the same machine refuses at once, so a fifteen-second outage
 * is enough to climb to a ten-second wait: fault injection measured the dependent
 * calls still failing 6.6 s after the restarted service was listening again. Every
 * {@value #WATCH_MILLIS} ms a channel found in {@code TRANSIENT_FAILURE} therefore has
 * its backoff reset, which bounds the time to notice a recovered service at about that.
 *
 * @param <S> the generated stub type
 */
public final class StubPool<S> implements AutoCloseable {

    static final long WATCH_MILLIS = 500;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "grpc-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    private final List<ManagedChannel> channels;
    private final List<S> stubs;
    private final AtomicInteger next = new AtomicInteger();
    private final ScheduledFuture<?> watch;

    private StubPool(List<ManagedChannel> channels, List<S> stubs) {
        this.channels = channels;
        this.stubs = stubs;
        this.watch = WATCHDOG.scheduleWithFixedDelay(this::reconnectFailedChannels, WATCH_MILLIS, WATCH_MILLIS, TimeUnit.MILLISECONDS);
    }

    public static <S> StubPool<S> connect(String target, int size, Function<ManagedChannel, S> stubFactory) {
        List<ManagedChannel> channels = new ArrayList<>(size);
        List<S> stubs = new ArrayList<>(size);
        for (int i = 0; i < Math.max(1, size); i++) {
            ManagedChannel channel = NettyChannelBuilder.forTarget(target).usePlaintext().build();
            channels.add(channel);
            stubs.add(stubFactory.apply(channel));
        }
        return new StubPool<>(channels, stubs);
    }

    /** The next stub. Callers stamp their own deadline on it; there is no default. */
    public S next() {
        return stubs.get(Math.floorMod(next.getAndIncrement(), stubs.size()));
    }

    private void reconnectFailedChannels() {
        for (ManagedChannel channel : channels) {
            if (channel.getState(false) == ConnectivityState.TRANSIENT_FAILURE) {
                channel.resetConnectBackoff();
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        watch.cancel(false);
        channels.forEach(ManagedChannel::shutdown);
        for (ManagedChannel channel : channels) {
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }
}
