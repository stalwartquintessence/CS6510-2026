package com.supermarket.api.mapper;

import com.supermarket.api.dto.TransactionDtos.ReceiptLineDto;
import com.supermarket.api.dto.TransactionDtos.ReceiptResponse;
import com.supermarket.api.dto.TransactionDtos.ScanResponse;
import com.supermarket.api.dto.TransactionDtos.TransactionResponse;
import com.supermarket.transaction.model.BasketView;
import com.supermarket.transaction.model.ReceiptView;
import com.supermarket.transaction.model.ScanView;

import java.util.List;

/**
 * Domain → wire for the transaction endpoints. This is also where {@link
 * java.time.Instant} becomes the contract's ISO-8601 string, so timestamp
 * formatting stays a presentation concern.
 */
public final class TransactionDtoMapper {

    private TransactionDtoMapper() {
    }

    public static TransactionResponse toResponse(BasketView basket) {
        return new TransactionResponse(
                basket.transactionId(),
                basket.stationId(),
                basket.status(),
                basket.itemCount(),
                basket.runningTotal(),
                basket.startedAt().toString());
    }

    public static ScanResponse toResponse(ScanView scan) {
        return new ScanResponse(
                scan.transactionId(),
                scan.sku(),
                scan.name(),
                scan.unitPrice(),
                scan.itemCount(),
                scan.runningTotal());
    }

    public static ReceiptResponse toResponse(ReceiptView receipt) {
        List<ReceiptLineDto> lines = receipt.lines().stream()
                .map(l -> new ReceiptLineDto(l.sku(), l.name(), l.unitPrice(), l.quantity()))
                .toList();
        return new ReceiptResponse(
                receipt.transactionId(),
                receipt.stationId(),
                receipt.itemCount(),
                receipt.totalAmount(),
                receipt.startedAt().toString(),
                receipt.completedAt().toString(),
                lines);
    }
}
