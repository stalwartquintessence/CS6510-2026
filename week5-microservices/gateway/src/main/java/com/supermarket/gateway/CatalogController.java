package com.supermarket.gateway;

import com.supermarket.contracts.ListItemsRequest;
import com.supermarket.gateway.dto.CatalogDtos.CatalogResponse;
import com.supermarket.gateway.rpc.ServiceClients;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

    private final ServiceClients services;

    public CatalogController(ServiceClients services) {
        this.services = services;
    }

    @GetMapping("/items")
    public CatalogResponse items() {
        return GatewayMapper.toResponse(services.catalog().listItems(ListItemsRequest.getDefaultInstance()));
    }
}
