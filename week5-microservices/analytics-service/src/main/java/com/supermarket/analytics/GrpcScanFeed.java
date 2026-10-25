package com.supermarket.analytics;

import com.supermarket.contracts.LatestScanIdRequest;
import com.supermarket.contracts.ReadScansRequest;
import com.supermarket.contracts.RpcErrors;
import com.supermarket.contracts.ScanFeedGrpc;
import com.supermarket.support.rpc.StubPool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
class GrpcScanFeed implements ScanFeed {

    private final StubPool<ScanFeedGrpc.ScanFeedBlockingStub> stubs;
    private final long deadlineMillis;

    GrpcScanFeed(StubPool<ScanFeedGrpc.ScanFeedBlockingStub> stubs, AnalyticsRpcProperties properties) {
        this.stubs = stubs;
        this.deadlineMillis = properties.deadline().toMillis();
    }

    @Override
    public List<ScanEvent> readAfter(long afterId, int limit) {
        return stub().readScans(ReadScansRequest.newBuilder().setAfterId(afterId).setLimit(limit).build())
                .getEventsList().stream()
                .map(e -> new ScanEvent(e.getId(), e.getSku(), RpcErrors.toInstant(e.getScannedAt())))
                .toList();
    }

    @Override
    public long latestId() {
        return stub().latestScanId(LatestScanIdRequest.getDefaultInstance()).getId();
    }

    private ScanFeedGrpc.ScanFeedBlockingStub stub() {
        return stubs.next().withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
}
