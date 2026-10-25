package com.supermarket.transaction;

import com.supermarket.contracts.Basket;
import com.supermarket.contracts.CompleteRequest;
import com.supermarket.contracts.GetStatusRequest;
import com.supermarket.contracts.Receipt;
import com.supermarket.contracts.ReceiptLine;
import com.supermarket.contracts.RpcErrors;
import com.supermarket.contracts.ScanRequest;
import com.supermarket.contracts.ScanResult;
import com.supermarket.contracts.StartRequest;
import com.supermarket.contracts.TransactionServiceGrpc;
import com.supermarket.transaction.model.BasketView;
import com.supermarket.transaction.model.ReceiptLineView;
import com.supermarket.transaction.model.ReceiptView;
import com.supermarket.transaction.model.ScanView;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/**
 * The transaction service's RPC surface. Plays the role the REST controller and its
 * mapper played in week 3: wire types in, view records out, nothing else.
 */
@Component
class TransactionGrpcService extends TransactionServiceGrpc.TransactionServiceImplBase {

    private final TransactionService transactionService;

    TransactionGrpcService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Override
    public void start(StartRequest request, StreamObserver<Basket> responseObserver) {
        reply(responseObserver, toMessage(transactionService.start(request.getStationId())));
    }

    @Override
    public void scan(ScanRequest request, StreamObserver<ScanResult> responseObserver) {
        ScanView scan = transactionService.scan(request.getTransactionId(), request.getSku());
        reply(responseObserver, ScanResult.newBuilder()
                .setTransactionId(scan.transactionId())
                .setSku(scan.sku())
                .setName(scan.name())
                .setUnitPrice(scan.unitPrice())
                .setItemCount(scan.itemCount())
                .setRunningTotal(scan.runningTotal())
                .build());
    }

    @Override
    public void complete(CompleteRequest request, StreamObserver<Receipt> responseObserver) {
        ReceiptView receipt = transactionService.complete(request.getTransactionId());
        Receipt.Builder message = Receipt.newBuilder()
                .setTransactionId(receipt.transactionId())
                .setStationId(receipt.stationId())
                .setItemCount(receipt.itemCount())
                .setTotalAmount(receipt.totalAmount())
                .setStartedAt(RpcErrors.toTimestamp(receipt.startedAt()))
                .setCompletedAt(RpcErrors.toTimestamp(receipt.completedAt()));
        for (ReceiptLineView line : receipt.lines()) {
            message.addLines(ReceiptLine.newBuilder()
                    .setSku(line.sku())
                    .setName(line.name())
                    .setUnitPrice(line.unitPrice())
                    .setQuantity(line.quantity()));
        }
        reply(responseObserver, message.build());
    }

    @Override
    public void getStatus(GetStatusRequest request, StreamObserver<Basket> responseObserver) {
        reply(responseObserver, toMessage(transactionService.getStatus(request.getTransactionId())));
    }

    private static Basket toMessage(BasketView basket) {
        return Basket.newBuilder()
                .setTransactionId(basket.transactionId())
                .setStationId(basket.stationId())
                .setStatus(basket.status())
                .setItemCount(basket.itemCount())
                .setRunningTotal(basket.runningTotal())
                .setStartedAt(RpcErrors.toTimestamp(basket.startedAt()))
                .build();
    }

    private static <T> void reply(StreamObserver<T> observer, T message) {
        observer.onNext(message);
        observer.onCompleted();
    }
}
