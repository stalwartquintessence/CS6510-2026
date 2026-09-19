package com.supermarket.service;

import com.supermarket.dto.Dtos.ReceiptLine;
import com.supermarket.dto.Dtos.ReceiptResponse;
import com.supermarket.dto.Dtos.ScanResponse;
import com.supermarket.dto.Dtos.TransactionResponse;
import com.supermarket.exception.ApiExceptions.BadRequestException;
import com.supermarket.exception.ApiExceptions.ConflictException;
import com.supermarket.exception.ApiExceptions.NotFoundException;
import com.supermarket.model.InventoryItem;
import com.supermarket.model.Transaction;
import com.supermarket.model.TransactionItem;
import com.supermarket.model.TransactionStatus;
import com.supermarket.repository.InventoryRepository;
import com.supermarket.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the transaction lifecycle: start → scan (one unit at a time) →
 * complete. Stock is touched only at completion, where each SKU is decremented
 * under a PESSIMISTIC_WRITE lock taken in a stable (SKU-sorted) order so
 * concurrent completions can never deadlock or oversell.
 */
@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryService inventoryService;
    private final AnalyticsService analyticsService;

    public TransactionService(TransactionRepository transactionRepository,
                              InventoryRepository inventoryRepository,
                              InventoryService inventoryService,
                              AnalyticsService analyticsService) {
        this.transactionRepository = transactionRepository;
        this.inventoryRepository = inventoryRepository;
        this.inventoryService = inventoryService;
        this.analyticsService = analyticsService;
    }

    @Transactional
    public TransactionResponse start(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            throw new BadRequestException("INVALID_REQUEST", "stationId is required");
        }
        Transaction tx = new Transaction("tx-" + UUID.randomUUID(), stationId);
        transactionRepository.save(tx);
        return toTransactionResponse(tx);
    }

    @Transactional
    public ScanResponse scan(String transactionId, String sku) {
        if (sku == null || sku.isBlank()) {
            throw new BadRequestException("INVALID_REQUEST", "sku is required");
        }
        Transaction tx = requireTransaction(transactionId);
        requireOpen(tx);

        InventoryItem item = inventoryRepository.findById(sku)
                .orElseThrow(() -> new NotFoundException("UNKNOWN_SKU", "No such SKU: " + sku));

        // One scan = one physical unit. Fold repeats of a SKU into a single line.
        TransactionItem line = tx.getLines().stream()
                .filter(l -> l.getSku().equals(sku))
                .findFirst()
                .orElse(null);
        if (line == null) {
            line = new TransactionItem(item.getSku(), item.getName(), item.getPrice(), 1);
            tx.getLines().add(line);
        } else {
            line.incrementQuantity();
        }

        double runningTotal = round2(basketTotal(tx));
        tx.setTotal(runningTotal);
        transactionRepository.save(tx);

        // Feed the analytics sliding window (persisted within this transaction).
        analyticsService.recordScan(item.getSku());

        return new ScanResponse(
                tx.getId(),
                item.getSku(),
                item.getName(),
                item.getPrice(),
                tx.itemCount(),
                runningTotal);
    }

    @Transactional
    public ReceiptResponse complete(String transactionId) {
        Transaction tx = requireTransaction(transactionId);
        requireOpen(tx);
        if (tx.getLines().isEmpty()) {
            throw new ConflictException("EMPTY_BASKET", "Cannot complete an empty transaction");
        }

        // Lock and decrement inventory in a deterministic SKU order so two
        // concurrent completions touching the same pair of SKUs acquire the
        // locks in the same order and cannot deadlock.
        List<TransactionItem> orderedLines = new ArrayList<>(tx.getLines());
        orderedLines.sort(Comparator.comparing(TransactionItem::getSku));
        for (TransactionItem line : orderedLines) {
            inventoryService.decrement(line.getSku(), line.getQuantity());
        }

        double totalAmount = round2(basketTotal(tx));
        tx.setTotal(totalAmount);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setCompletedAt(Instant.now());
        transactionRepository.save(tx);

        List<ReceiptLine> receiptLines = new ArrayList<>(orderedLines.size());
        for (TransactionItem line : orderedLines) {
            receiptLines.add(new ReceiptLine(
                    line.getSku(), line.getName(), line.getUnitPrice(), line.getQuantity()));
        }

        return new ReceiptResponse(
                tx.getId(),
                tx.getStationId(),
                tx.itemCount(),
                totalAmount,
                tx.getCreatedAt().toString(),
                tx.getCompletedAt().toString(),
                receiptLines);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getStatus(String transactionId) {
        return toTransactionResponse(requireTransaction(transactionId));
    }

    // --- helpers -----------------------------------------------------------

    private Transaction requireTransaction(String transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException(
                        "TRANSACTION_NOT_FOUND", "No such transaction: " + transactionId));
    }

    private void requireOpen(Transaction tx) {
        if (tx.getStatus() != TransactionStatus.OPEN) {
            throw new ConflictException(
                    "TRANSACTION_NOT_OPEN",
                    "Transaction " + tx.getId() + " is " + tx.getStatus());
        }
    }

    private TransactionResponse toTransactionResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getStationId(),
                tx.getStatus().name(),
                tx.itemCount(),
                round2(tx.getTotal()),
                tx.getCreatedAt().toString());
    }

    private static double basketTotal(Transaction tx) {
        double total = 0.0;
        for (TransactionItem line : tx.getLines()) {
            total += line.getUnitPrice() * line.getQuantity();
        }
        return total;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
