package com.supermarket.gateway;

import com.supermarket.contracts.PopularItemsRequest;
import com.supermarket.gateway.dto.AnalyticsDtos.PopularItemsResponse;
import com.supermarket.gateway.rpc.ServiceClients;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final ServiceClients services;

    public AnalyticsController(ServiceClients services) {
        this.services = services;
    }

    @GetMapping("/popular-items")
    public PopularItemsResponse popularItems(
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return GatewayMapper.toResponse(
                services.analytics().getPopularItems(PopularItemsRequest.newBuilder().setLimit(limit).build()));
    }
}
