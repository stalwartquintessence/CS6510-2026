package com.supermarket.gateway;

import com.supermarket.contracts.LowStockRequest;
import com.supermarket.gateway.dto.InventoryDtos.LowStockResponse;
import com.supermarket.gateway.rpc.ServiceClients;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final ServiceClients services;

    public InventoryController(ServiceClients services) {
        this.services = services;
    }

    /** {@code threshold} is optional; omitted means the inventory service's configured default. */
    @GetMapping("/low-stock")
    public LowStockResponse lowStock(@RequestParam(required = false) Integer threshold) {
        LowStockRequest.Builder request = LowStockRequest.newBuilder();
        if (threshold != null) {
            request.setThreshold(threshold);
        }
        return GatewayMapper.toResponse(services.inventory().getLowStock(request.build()));
    }
}
