package com.supermarket.inventory;

import com.supermarket.contracts.InventoryServiceGrpc;
import com.supermarket.contracts.LowStockAlert;
import com.supermarket.contracts.LowStockReport;
import com.supermarket.contracts.LowStockRequest;
import com.supermarket.contracts.RpcErrors;
import com.supermarket.inventory.model.LowStockAlertView;
import com.supermarket.inventory.model.LowStockView;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/** The inventory service's RPC surface: a thin adapter over {@link InventoryService}. */
@Component
class InventoryGrpcService extends InventoryServiceGrpc.InventoryServiceImplBase {

    private final InventoryService inventoryService;

    InventoryGrpcService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public void getLowStock(LowStockRequest request, StreamObserver<LowStockReport> responseObserver) {
        Integer threshold = request.hasThreshold() ? request.getThreshold() : null;
        LowStockView view = inventoryService.lowStock(threshold);

        LowStockReport.Builder report = LowStockReport.newBuilder()
                .setThreshold(view.threshold())
                .setGeneratedAt(RpcErrors.toTimestamp(view.generatedAt()));
        for (LowStockAlertView alert : view.alerts()) {
            report.addAlerts(LowStockAlert.newBuilder()
                    .setSku(alert.sku())
                    .setName(alert.name())
                    .setCurrentStock(alert.currentStock())
                    .setThreshold(alert.threshold()));
        }
        responseObserver.onNext(report.build());
        responseObserver.onCompleted();
    }
}
