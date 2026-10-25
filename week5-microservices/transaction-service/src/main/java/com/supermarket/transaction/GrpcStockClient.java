package com.supermarket.transaction;

import com.supermarket.contracts.DeductStockRequest;
import com.supermarket.contracts.RestoreStockRequest;
import com.supermarket.contracts.StockLine;
import com.supermarket.contracts.StockServiceGrpc;
import com.supermarket.support.rpc.StubPool;
import com.supermarket.transaction.domain.BasketLine;
import io.grpc.Context;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
class GrpcStockClient implements StockClient {

    private final StubPool<StockServiceGrpc.StockServiceBlockingStub> stubs;
    private final Duration deductDeadline;
    private final Duration restoreDeadline;

    GrpcStockClient(StubPool<StockServiceGrpc.StockServiceBlockingStub> stubs, SagaProperties properties) {
        this.stubs = stubs;
        this.deductDeadline = properties.deductDeadline();
        this.restoreDeadline = properties.restoreDeadline();
    }

    /**
     * Runs in the incoming call's gRPC context, so if the gateway gives up the stock
     * call is cancelled too (and then compensated).
     */
    @Override
    public boolean deduct(String saleId, List<BasketLine> lines) {
        DeductStockRequest.Builder request = DeductStockRequest.newBuilder().setSaleId(saleId);
        for (BasketLine line : lines) {
            request.addLines(StockLine.newBuilder().setSku(line.sku()).setQuantity(line.quantity()));
        }
        return stub(deductDeadline).deductStock(request.build()).getApplied();
    }

    /**
     * Runs in the root context, not the incoming call's: compensation usually happens
     * <em>because</em> that call's deadline expired, and a call made in an expired
     * context fails at once. The compensation gets its own deadline instead.
     */
    @Override
    public int restore(String saleId) {
        Context previous = Context.ROOT.attach();
        try {
            return stub(restoreDeadline)
                    .restoreStock(RestoreStockRequest.newBuilder().setSaleId(saleId).build())
                    .getUnitsRestored();
        } finally {
            Context.ROOT.detach(previous);
        }
    }

    private StockServiceGrpc.StockServiceBlockingStub stub(Duration deadline) {
        return stubs.next().withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }
}
