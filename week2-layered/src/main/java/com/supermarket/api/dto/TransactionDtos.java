package com.supermarket.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Wire shapes for the {@code /transactions} endpoints. */
public final class TransactionDtos {

    private TransactionDtos() {
    }

    // --- requests ----------------------------------------------------------
    public record StartTransactionRequest(@NotBlank String stationId) {
    }

    public record ScanItemRequest(@NotBlank String sku) {
    }

    // --- responses ---------------------------------------------------------
    public record TransactionResponse(
            String transactionId,
            String stationId,
            String status,
            int itemCount,
            double runningTotal,
            String startedAt) {
    }

    public record ScanResponse(
            String transactionId,
            String sku,
            String name,
            double unitPrice,
            int itemCount,
            double runningTotal) {
    }

    public record ReceiptLineDto(String sku, String name, double unitPrice, int quantity) {
    }

    public record ReceiptResponse(
            String transactionId,
            String stationId,
            int itemCount,
            double totalAmount,
            String startedAt,
            String completedAt,
            List<ReceiptLineDto> lines) {
    }
}
