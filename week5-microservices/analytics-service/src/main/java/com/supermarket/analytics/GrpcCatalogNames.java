package com.supermarket.analytics;

import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.GetItemRequest;
import com.supermarket.support.rpc.StubPool;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
class GrpcCatalogNames implements CatalogNames {

    private final StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> stubs;
    private final long deadlineMillis;

    GrpcCatalogNames(StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> stubs, AnalyticsRpcProperties properties) {
        this.stubs = stubs;
        this.deadlineMillis = properties.deadline().toMillis();
    }

    @Override
    public Optional<String> nameOf(String sku) {
        try {
            return Optional.of(stubs.next()
                    .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                    .getItem(GetItemRequest.newBuilder().setSku(sku).build())
                    .getName());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
