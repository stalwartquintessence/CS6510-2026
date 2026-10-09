package com.supermarket.transaction;

import com.supermarket.domain.Basket;
import com.supermarket.domain.BasketLine;
import com.supermarket.domain.ItemSnapshot;
import com.supermarket.domain.error.InvalidRequestException;
import com.supermarket.domain.error.ItemNotFoundException;
import com.supermarket.domain.error.TransactionNotFoundException;
import com.supermarket.persistence.ScanLogDao;
import com.supermarket.persistence.TransactionDao;
import com.supermarket.transaction.model.BasketView;
import com.supermarket.transaction.model.ReceiptLineView;
import com.supermarket.transaction.model.ReceiptView;
import com.supermarket.transaction.model.ScanView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates checkout. Stock is touched only at completion, where each SKU is
 * decremented under a PESSIMISTIC_WRITE row lock taken in a stable (SKU-sorted)
 * order so concurrent completions can never deadlock or oversell.
 *
 * <p>Week 4: everything a sale needs — the basket, the stock rows, the scan log —
 * is in the one shared database, so completion is still a single local ACID
 * transaction. No other service is called, and nothing here can be half-applied.
 */
@Service
class DefaultTransactionService implements TransactionService {

    private final TransactionDao transactionDao;
    private final StockLedger stockLedger;
    private final ScanLogDao scanLogDao;

    DefaultTransactionService(TransactionDao transactionDao,
                              StockLedger stockLedger,
                              ScanLogDao scanLogDao) {
        this.transactionDao = transactionDao;
        this.stockLedger = stockLedger;
        this.scanLogDao = scanLogDao;
    }

    @Override
    @Transactional
    public BasketView start(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            throw new InvalidRequestException("stationId is required");
        }
        Basket basket = transactionDao.create("tx-" + UUID.randomUUID(), stationId);
        return toView(basket);
    }

    @Override
    @Transactional
    public ScanView scan(String transactionId, String sku) {
        if (sku == null || sku.isBlank()) {
            throw new InvalidRequestException("sku is required");
        }
        Basket basket = requireBasket(transactionId);
        basket.requireOpen();

        ItemSnapshot item = stockLedger.findItem(sku)
                .orElseThrow(() -> new ItemNotFoundException(sku));

        basket.addUnit(item);
        transactionDao.save(basket);

        // The analytics service tails this table; the two services never call each other.
        // Same transaction as the basket write, so a rolled-back scan is never counted.
        scanLogDao.append(item.sku(), Instant.now());

        return new ScanView(
                basket.transactionId(),
                item.sku(),
                item.name(),
                item.price(),
                basket.itemCount(),
                basket.total());
    }

    @Override
    @Transactional
    public ReceiptView complete(String transactionId) {
        Basket basket = requireBasket(transactionId);
        basket.requireOpen();

        // Lock and decrement in a deterministic SKU order so two concurrent
        // completions touching the same pair of SKUs acquire the locks in the
        // same order and cannot deadlock.
        List<BasketLine> orderedLines = basket.linesSortedBySku();
        for (BasketLine line : orderedLines) {
            stockLedger.decrement(line.sku(), line.quantity());
        }

        basket.markCompleted(Instant.now());
        transactionDao.save(basket);

        List<ReceiptLineView> receiptLines = new ArrayList<>(orderedLines.size());
        for (BasketLine line : orderedLines) {
            receiptLines.add(new ReceiptLineView(
                    line.sku(), line.name(), line.unitPrice(), line.quantity()));
        }

        return new ReceiptView(
                basket.transactionId(),
                basket.stationId(),
                basket.itemCount(),
                basket.total(),
                basket.startedAt(),
                basket.completedAt(),
                receiptLines);
    }

    @Override
    @Transactional(readOnly = true)
    public BasketView getStatus(String transactionId) {
        return toView(requireBasket(transactionId));
    }

    private Basket requireBasket(String transactionId) {
        return transactionDao.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    private static BasketView toView(Basket basket) {
        return new BasketView(
                basket.transactionId(),
                basket.stationId(),
                basket.status().name(),
                basket.itemCount(),
                basket.total(),
                basket.startedAt());
    }
}
