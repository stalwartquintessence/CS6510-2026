package com.supermarket.dto;

import java.util.List;

/**
 * Response DTOs for the self-checkout API, grouped in one file for brevity.
 * Field names and shapes match spec/self-checkout-openapi.yaml exactly — the
 * load client parses these by name, so they must not drift.
 */
public final class Dtos {

    private Dtos() {
    }

    // --- /items ------------------------------------------------------------
    public record CatalogItem(String sku, String name, double price) {
    }

    public record CatalogResponse(List<CatalogItem> items) {
    }

    // --- POST /transactions, GET /transactions/{id} ------------------------
    public record TransactionResponse(
            String transactionId,
            String stationId,
            String status,
            int itemCount,
            double runningTotal,
            String startedAt) {
    }

    // --- POST /transactions/{id}/items -------------------------------------
    public record ScanResponse(
            String transactionId,
            String sku,
            String name,
            double unitPrice,
            int itemCount,
            double runningTotal) {
    }

    // --- POST /transactions/{id}/complete ----------------------------------
    public record ReceiptLine(String sku, String name, double unitPrice, int quantity) {
    }

    public record ReceiptResponse(
            String transactionId,
            String stationId,
            int itemCount,
            double totalAmount,
            String startedAt,
            String completedAt,
            List<ReceiptLine> lines) {
    }

    // --- GET /inventory/low-stock ------------------------------------------
    public record LowStockAlert(
            String sku,
            String name,
            int currentStock,
            int threshold,
            String triggeredAt) {
    }

    public record LowStockResponse(
            int threshold,
            String generatedAt,
            List<LowStockAlert> alerts) {
    }

    // --- GET /analytics/popular-items --------------------------------------
    public record PopularItemDto(String sku, String name, int scanCount, int rank) {
    }

    public record PopularItemsResponse(
            int windowSize,
            int slideInterval,
            long windowStart,
            long windowEnd,
            String computedAt,
            List<PopularItemDto> items) {
    }

    // --- errors ------------------------------------------------------------
    public record ApiError(String error, String message) {
    }

    // --- request bodies ----------------------------------------------------
    public record StartTransactionRequest(String stationId) {
    }

    public record ScanItemRequest(String sku) {
    }
}
