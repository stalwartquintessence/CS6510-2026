package com.supermarket.api;

import com.supermarket.api.dto.InventoryDtos.LowStockResponse;
import com.supermarket.api.mapper.InventoryDtoMapper;
import com.supermarket.transaction.inventory.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** {@code threshold} is optional; omitted means the server's configured default. */
    @GetMapping("/low-stock")
    public LowStockResponse lowStock(@RequestParam(required = false) Integer threshold) {
        return InventoryDtoMapper.toResponse(inventoryService.lowStock(threshold));
    }
}
