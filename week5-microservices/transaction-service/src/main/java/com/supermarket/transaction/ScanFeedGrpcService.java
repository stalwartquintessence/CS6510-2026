package com.supermarket.transaction;

import com.supermarket.contracts.LatestScanIdRequest;
import com.supermarket.contracts.ReadScansRequest;
import com.supermarket.contracts.RpcErrors;
import com.supermarket.contracts.ScanBatch;
import com.supermarket.contracts.ScanFeedEvent;
import com.supermarket.contracts.ScanFeedGrpc;
import com.supermarket.contracts.ScanId;
import com.supermarket.transaction.domain.ScanEvent;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** The outbox as an RPC: a thin adapter over {@link ScanFeedService}. Internal; only analytics holds a stub. */
@Component
class ScanFeedGrpcService extends ScanFeedGrpc.ScanFeedImplBase {

    private final ScanFeedService feed;

    ScanFeedGrpcService(ScanFeedService feed) {
        this.feed = feed;
    }

    @Override
    public void readScans(ReadScansRequest request, StreamObserver<ScanBatch> responseObserver) {
        ScanBatch.Builder batch = ScanBatch.newBuilder();
        for (ScanEvent event : feed.after(request.getAfterId(), request.getLimit())) {
            batch.addEvents(ScanFeedEvent.newBuilder()
                    .setId(event.id())
                    .setSku(event.sku())
                    .setScannedAt(RpcErrors.toTimestamp(event.scannedAt())));
        }
        responseObserver.onNext(batch.build());
        responseObserver.onCompleted();
    }

    @Override
    public void latestScanId(LatestScanIdRequest request, StreamObserver<ScanId> responseObserver) {
        responseObserver.onNext(ScanId.newBuilder().setId(feed.latestId()).build());
        responseObserver.onCompleted();
    }
}
