package com.supermarket.analytics;

import com.supermarket.analytics.model.PopularItemView;
import com.supermarket.analytics.model.PopularItemsView;
import com.supermarket.contracts.AnalyticsServiceGrpc;
import com.supermarket.contracts.PopularItem;
import com.supermarket.contracts.PopularItemsReport;
import com.supermarket.contracts.PopularItemsRequest;
import com.supermarket.contracts.RpcErrors;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** The analytics service's RPC surface: a thin adapter over {@link AnalyticsService}. */
@Component
class AnalyticsGrpcService extends AnalyticsServiceGrpc.AnalyticsServiceImplBase {

    private final AnalyticsService analyticsService;

    AnalyticsGrpcService(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Override
    public void getPopularItems(PopularItemsRequest request, StreamObserver<PopularItemsReport> responseObserver) {
        PopularItemsView view = analyticsService.getPopular(request.getLimit());

        PopularItemsReport.Builder report = PopularItemsReport.newBuilder()
                .setWindowSize(view.windowSize())
                .setSlideInterval(view.slideInterval())
                .setWindowStart(view.windowStart())
                .setWindowEnd(view.windowEnd())
                .setComputedAt(RpcErrors.toTimestamp(view.computedAt()));
        for (PopularItemView item : view.items()) {
            report.addItems(PopularItem.newBuilder()
                    .setSku(item.sku())
                    .setName(item.name())
                    .setScanCount(item.scanCount())
                    .setRank(item.rank()));
        }
        responseObserver.onNext(report.build());
        responseObserver.onCompleted();
    }
}
