package com.supermarket.controller;

import com.supermarket.dto.Dtos.LowStockResponse;
import com.supermarket.service.InventoryService;
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

    @GetMapping("/low-stock")
    public LowStockResponse lowStock(@RequestParam(required = false) Integer threshold) {
        return inventoryService.lowStock(threshold);
    }
}
