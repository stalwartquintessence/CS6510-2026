package com.supermarket.gateway.rpc;

import com.supermarket.contracts.AnalyticsServiceGrpc;
import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.InventoryServiceGrpc;
import com.supermarket.contracts.TransactionServiceGrpc;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The gateway's four gRPC clients, one per public service. Each call picks the next
 * stub round-robin and stamps it with the configured deadline.
 *
 * <p>Week 5's one change to the gateway: every 500 ms a channel in
 * {@code TRANSIENT_FAILURE} has its reconnect backoff reset. Fault injection showed
 * that without it gRPC's exponential backoff kept the gateway failing requests for a
 * service 6.6 s after that service had restarted (see the chassis's {@code StubPool},
 * which does the same for service-to-service calls).
 */
@Component
public class ServiceClients implements DisposableBean {

    private static final long WATCH_MILLIS = 500;

    private final List<ManagedChannel> channels = new ArrayList<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "grpc-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final Pool<CatalogServiceGrpc.CatalogServiceBlockingStub> catalog;
    private final Pool<TransactionServiceGrpc.TransactionServiceBlockingStub> transaction;
    private final Pool<InventoryServiceGrpc.InventoryServiceBlockingStub> inventory;
    private final Pool<AnalyticsServiceGrpc.AnalyticsServiceBlockingStub> analytics;
    private final long deadlineMillis;

    ServiceClients(RpcProperties properties) {
        this.deadlineMillis = properties.deadline().toMillis();
        int n = properties.channelsPerService();
        this.catalog = pool(properties.catalog(), n, CatalogServiceGrpc::newBlockingStub);
        this.transaction = pool(properties.transaction(), n, TransactionServiceGrpc::newBlockingStub);
        this.inventory = pool(properties.inventory(), n, InventoryServiceGrpc::newBlockingStub);
        this.analytics = pool(properties.analytics(), n, AnalyticsServiceGrpc::newBlockingStub);
        watchdog.scheduleWithFixedDelay(this::reconnectFailedChannels, WATCH_MILLIS, WATCH_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void reconnectFailedChannels() {
        for (ManagedChannel channel : channels) {
            if (channel.getState(false) == ConnectivityState.TRANSIENT_FAILURE) {
                channel.resetConnectBackoff();
            }
        }
    }

    public CatalogServiceGrpc.CatalogServiceBlockingStub catalog() {
        return catalog.next().withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    public TransactionServiceGrpc.TransactionServiceBlockingStub transaction() {
        return transaction.next().withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    public InventoryServiceGrpc.InventoryServiceBlockingStub inventory() {
        return inventory.next().withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    public AnalyticsServiceGrpc.AnalyticsServiceBlockingStub analytics() {
        return analytics.next().withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    private <S> Pool<S> pool(String target, int size, Function<ManagedChannel, S> stubFactory) {
        List<S> stubs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ManagedChannel channel = NettyChannelBuilder.forTarget(target).usePlaintext().build();
            channels.add(channel);
            stubs.add(stubFactory.apply(channel));
        }
        return new Pool<>(stubs);
    }

    @Override
    public void destroy() throws InterruptedException {
        watchdog.shutdownNow();
        channels.forEach(ManagedChannel::shutdown);
        for (ManagedChannel channel : channels) {
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }

    private static final class Pool<S> {
        private final List<S> stubs;
        private final AtomicInteger next = new AtomicInteger();

        Pool(List<S> stubs) {
            this.stubs = stubs;
        }

        S next() {
            return stubs.get(Math.floorMod(next.getAndIncrement(), stubs.size()));
        }
    }
}
