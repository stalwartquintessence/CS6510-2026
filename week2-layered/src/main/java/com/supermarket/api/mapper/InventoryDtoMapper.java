package com.supermarket.api.mapper;

import com.supermarket.api.dto.InventoryDtos.LowStockAlertDto;
import com.supermarket.api.dto.InventoryDtos.LowStockResponse;
import com.supermarket.transaction.inventory.model.LowStockView;

import java.util.List;

/** Domain → wire for the low-stock endpoint. */
public final class InventoryDtoMapper {

    private InventoryDtoMapper() {
    }

    public static LowStockResponse toResponse(LowStockView view) {
        String generatedAt = view.generatedAt().toString();
        List<LowStockAlertDto> alerts = view.alerts().stream()
                .map(a -> new LowStockAlertDto(
                        a.sku(), a.name(), a.currentStock(), a.threshold(), generatedAt))
                .toList();
        return new LowStockResponse(view.threshold(), generatedAt, alerts);
    }
}
