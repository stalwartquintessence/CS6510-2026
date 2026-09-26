package com.supermarket.api.dto;

import java.util.List;

/** Wire shapes for {@code GET /inventory/low-stock}. */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record LowStockAlertDto(
            String sku,
            String name,
            int currentStock,
            int threshold,
            String triggeredAt) {
    }

    public record LowStockResponse(
            int threshold,
            String generatedAt,
            List<LowStockAlertDto> alerts) {
    }
}
