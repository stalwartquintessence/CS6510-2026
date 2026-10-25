package com.supermarket.transaction;

import com.supermarket.support.error.TransactionNotFoundException;
import com.supermarket.transaction.domain.Basket;
import com.supermarket.transaction.domain.BasketStatus;
import com.supermarket.transaction.domain.ItemSnapshot;
import com.supermarket.transaction.domain.SagaRef;
import com.supermarket.transaction.persistence.ScanEventDao;
import com.supermarket.transaction.persistence.TransactionDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every local ACID transaction the transaction service runs against its own
 * database. Each method is one short transaction that commits before it returns.
 *
 * <p>In week 4 completion was one {@code @Transactional} method that also took the
 * stock row locks. Here the stock lives in another service, so the saga calls these
 * one at a time with the remote stock step between them, and no database connection
 * or lock is ever held across a network call.
 */
@Component
class BasketStore {

    private final TransactionDao transactions;
    private final ScanEventDao scanEvents;

    BasketStore(TransactionDao transactions, ScanEventDao scanEvents) {
        this.transactions = transactions;
        this.scanEvents = scanEvents;
    }

    @Transactional
    Basket open(String stationId) {
        return transactions.create("tx-" + UUID.randomUUID(), stationId);
    }

    /** Scan one unit of an already-resolved item into an open basket, and record it in the outbox. */
    @Transactional
    Basket addUnit(String transactionId, ItemSnapshot item) {
        Basket basket = requireForUpdate(transactionId);
        basket.requireOpen();
        basket.addUnit(item);
        transactions.save(basket);

        // The analytics service reads this outbox through the ScanFeed RPC; the two
        // services share no table. Same transaction as the basket write, so a
        // rolled-back scan is never counted.
        scanEvents.append(item.sku(), Instant.now());
        return basket;
    }

    @Transactional(readOnly = true)
    Basket find(String transactionId) {
        return transactions.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

    /**
     * Saga step 1: OPEN → COMPLETING, as a new attempt. Under the row lock, so a
     * concurrent scan or a second complete for the same basket waits and then sees
     * it is no longer open.
     */
    @Transactional
    Basket freeze(String transactionId) {
        Basket basket = requireForUpdate(transactionId);
        basket.beginCompletion();
        transactions.save(basket);
        return basket;
    }

    /** Saga step 3: COMPLETING → COMPLETED, if this attempt still owns the saga. */
    @Transactional
    boolean markCompleted(String transactionId, int attempt, Instant completedAt) {
        return transactions.markCompleted(transactionId, attempt, completedAt);
    }

    /** Any other saga transition, if this attempt is still at {@code from}. */
    @Transactional
    boolean move(String transactionId, int attempt, BasketStatus from, BasketStatus to) {
        return transactions.transition(transactionId, attempt, from, to);
    }

    @Transactional(readOnly = true)
    List<SagaRef> inFlight(Instant before, int limit) {
        return transactions.findInFlight(before, limit);
    }

    private Basket requireForUpdate(String transactionId) {
        return transactions.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }
}
