package com.supermarket.inventory;

import com.supermarket.contracts.AppliedLine;
import com.supermarket.contracts.DeductStockRequest;
import com.supermarket.contracts.DeductStockResult;
import com.supermarket.contracts.RestoreStockRequest;
import com.supermarket.contracts.RestoreStockResult;
import com.supermarket.contracts.StockLine;
import com.supermarket.contracts.StockServiceGrpc;
import com.supermarket.inventory.model.DeductOutcome;
import com.supermarket.inventory.model.RequestedLine;
import com.supermarket.inventory.model.SaleLine;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The saga's stock step as an RPC: a thin adapter over {@link StockLedger}. Internal:
 * only the transaction service holds a {@code StockService} stub (ArchUnit), and the
 * gateway exposes nothing that reaches it.
 */
@Component
class StockGrpcService extends StockServiceGrpc.StockServiceImplBase {

    private final StockLedger ledger;

    StockGrpcService(StockLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public void deductStock(DeductStockRequest request, StreamObserver<DeductStockResult> responseObserver) {
        List<RequestedLine> lines = new ArrayList<>(request.getLinesCount());
        for (StockLine line : request.getLinesList()) {
            lines.add(new RequestedLine(line.getSku(), line.getQuantity()));
        }
        DeductOutcome outcome = ledger.deduct(request.getSaleId(), lines);

        DeductStockResult.Builder result = DeductStockResult.newBuilder()
                .setSaleId(outcome.saleId())
                .setApplied(outcome.applied());
        for (SaleLine line : outcome.lines()) {
            result.addLines(AppliedLine.newBuilder()
                    .setSku(line.sku())
                    .setRequested(line.requested())
                    .setApplied(line.applied()));
        }
        responseObserver.onNext(result.build());
        responseObserver.onCompleted();
    }

    @Override
    public void restoreStock(RestoreStockRequest request, StreamObserver<RestoreStockResult> responseObserver) {
        int units = ledger.restore(request.getSaleId());
        responseObserver.onNext(RestoreStockResult.newBuilder()
                .setSaleId(request.getSaleId())
                .setUnitsRestored(units)
                .build());
        responseObserver.onCompleted();
    }
}
