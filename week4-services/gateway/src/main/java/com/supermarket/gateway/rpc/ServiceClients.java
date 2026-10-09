package com.supermarket.gateway.rpc;

import com.supermarket.contracts.AnalyticsServiceGrpc;
import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.InventoryServiceGrpc;
import com.supermarket.contracts.TransactionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The gateway's four gRPC clients, one per domain service. Each call picks the next
 * stub round-robin and stamps it with the configured deadline.
 */
@Component
public class ServiceClients implements DisposableBean {

    private final List<ManagedChannel> channels = new ArrayList<>();
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
