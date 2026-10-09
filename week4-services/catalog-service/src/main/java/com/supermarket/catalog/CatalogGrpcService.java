package com.supermarket.catalog;

import com.supermarket.contracts.CatalogItem;
import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.ListItemsRequest;
import com.supermarket.contracts.ListItemsResponse;
import com.supermarket.domain.ItemSnapshot;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** The catalog service's RPC surface: a thin adapter over {@link CatalogService}. */
@Component
class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final CatalogService catalogService;

    CatalogGrpcService(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public void listItems(ListItemsRequest request, StreamObserver<ListItemsResponse> responseObserver) {
        ListItemsResponse.Builder response = ListItemsResponse.newBuilder();
        for (ItemSnapshot item : catalogService.catalog()) {
            response.addItems(CatalogItem.newBuilder()
                    .setSku(item.sku())
                    .setName(item.name())
                    .setPrice(item.price()));
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }
}
