package com.supermarket.gateway;

import com.supermarket.contracts.Basket;
import com.supermarket.contracts.ListItemsResponse;
import com.supermarket.contracts.LowStockReport;
import com.supermarket.contracts.PopularItemsReport;
import com.supermarket.contracts.Receipt;
import com.supermarket.contracts.RpcErrors;
import com.supermarket.contracts.ScanResult;
import com.supermarket.gateway.dto.AnalyticsDtos.PopularItemDto;
import com.supermarket.gateway.dto.AnalyticsDtos.PopularItemsResponse;
import com.supermarket.gateway.dto.CatalogDtos.CatalogItemDto;
import com.supermarket.gateway.dto.CatalogDtos.CatalogResponse;
import com.supermarket.gateway.dto.InventoryDtos.LowStockAlertDto;
import com.supermarket.gateway.dto.InventoryDtos.LowStockResponse;
import com.supermarket.gateway.dto.TransactionDtos.ReceiptLineDto;
import com.supermarket.gateway.dto.TransactionDtos.ReceiptResponse;
import com.supermarket.gateway.dto.TransactionDtos.ScanResponse;
import com.supermarket.gateway.dto.TransactionDtos.TransactionResponse;

import java.util.List;

/**
 * gRPC message → REST body, for every endpoint. This is where a protobuf
 * {@code Timestamp} becomes the contract's ISO-8601 string; the field names and
 * shapes on the right-hand side are fixed by the OpenAPI contract and the load client.
 */
final class GatewayMapper {

    private GatewayMapper() {
    }

    static CatalogResponse toResponse(ListItemsResponse message) {
        List<CatalogItemDto> items = message.getItemsList().stream()
                .map(i -> new CatalogItemDto(i.getSku(), i.getName(), i.getPrice()))
                .toList();
        return new CatalogResponse(items);
    }

    static TransactionResponse toResponse(Basket message) {
        return new TransactionResponse(
                message.getTransactionId(),
                message.getStationId(),
                message.getStatus(),
                message.getItemCount(),
                message.getRunningTotal(),
                RpcErrors.toInstant(message.getStartedAt()).toString());
    }

    static ScanResponse toResponse(ScanResult message) {
        return new ScanResponse(
                message.getTransactionId(),
                message.getSku(),
                message.getName(),
                message.getUnitPrice(),
                message.getItemCount(),
                message.getRunningTotal());
    }

    static ReceiptResponse toResponse(Receipt message) {
        List<ReceiptLineDto> lines = message.getLinesList().stream()
                .map(l -> new ReceiptLineDto(l.getSku(), l.getName(), l.getUnitPrice(), l.getQuantity()))
                .toList();
        return new ReceiptResponse(
                message.getTransactionId(),
                message.getStationId(),
                message.getItemCount(),
                message.getTotalAmount(),
                RpcErrors.toInstant(message.getStartedAt()).toString(),
                RpcErrors.toInstant(message.getCompletedAt()).toString(),
                lines);
    }

    static LowStockResponse toResponse(LowStockReport message) {
        String generatedAt = RpcErrors.toInstant(message.getGeneratedAt()).toString();
        List<LowStockAlertDto> alerts = message.getAlertsList().stream()
                .map(a -> new LowStockAlertDto(
                        a.getSku(), a.getName(), a.getCurrentStock(), a.getThreshold(), generatedAt))
                .toList();
        return new LowStockResponse(message.getThreshold(), generatedAt, alerts);
    }

    static PopularItemsResponse toResponse(PopularItemsReport message) {
        List<PopularItemDto> items = message.getItemsList().stream()
                .map(i -> new PopularItemDto(i.getSku(), i.getName(), i.getScanCount(), i.getRank()))
                .toList();
        return new PopularItemsResponse(
                message.getWindowSize(),
                message.getSlideInterval(),
                message.getWindowStart(),
                message.getWindowEnd(),
                RpcErrors.toInstant(message.getComputedAt()).toString(),
                items);
    }
}
