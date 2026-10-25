package com.supermarket.transaction;

import com.supermarket.contracts.CatalogItem;
import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.GetItemRequest;
import com.supermarket.contracts.ListItemsRequest;
import com.supermarket.support.error.ItemNotFoundException;
import com.supermarket.support.error.ServiceUnavailableException;
import com.supermarket.support.rpc.StubPool;
import com.supermarket.transaction.domain.ItemSnapshot;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
class GrpcCatalogClient implements CatalogClient {

    private final StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> stubs;
    private final long deadlineMillis;

    GrpcCatalogClient(StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> stubs, TransactionRpcProperties properties) {
        this.stubs = stubs;
        this.deadlineMillis = properties.catalogDeadline().toMillis();
    }

    @Override
    public ItemSnapshot item(String sku) {
        try {
            return toSnapshot(stub().getItem(GetItemRequest.newBuilder().setSku(sku).build()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ItemNotFoundException(sku);
            }
            throw new ServiceUnavailableException("CATALOG_UNAVAILABLE",
                    "Could not look up " + sku + " in the catalog: " + e.getStatus().getCode());
        }
    }

    @Override
    public List<ItemSnapshot> all() {
        return stub().listItems(ListItemsRequest.getDefaultInstance()).getItemsList().stream()
                .map(GrpcCatalogClient::toSnapshot)
                .toList();
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub stub() {
        return stubs.next().withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    private static ItemSnapshot toSnapshot(CatalogItem item) {
        return new ItemSnapshot(item.getSku(), item.getName(), item.getPrice());
    }
}
