package com.supermarket.transaction.domain;

import com.supermarket.support.error.EmptyBasketException;
import com.supermarket.support.error.TransactionNotOpenException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The checkout aggregate: a station's basket and its lifecycle.
 *
 * <p>Deliberately free of JPA — the persistence package maps it to and from
 * {@code TransactionEntity}. That is what lets the service hold the business
 * rules (fold repeat scans, compute totals, refuse to complete twice) without
 * the rest of it ever seeing a managed entity.
 *
 * <p>Week 5: completion is a saga across two services, so the basket no longer
 * goes straight to COMPLETED. {@link #beginCompletion()} freezes it and numbers the
 * attempt; the saga moves it on from there (see {@link BasketStatus}).
 */
public final class Basket {

    private final String transactionId;
    private final String stationId;
    private final Instant startedAt;
    private final List<BasketLine> lines;
    private BasketStatus status;
    private Instant completedAt;
    private int sagaAttempt;

    public Basket(String transactionId,
                  String stationId,
                  BasketStatus status,
                  Instant startedAt,
                  Instant completedAt,
                  int sagaAttempt,
                  List<BasketLine> lines) {
        this.transactionId = transactionId;
        this.stationId = stationId;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.sagaAttempt = sagaAttempt;
        this.lines = new ArrayList<>(lines);
    }

    /** Add one physical unit of an item. One scan = one unit, per the contract. */
    public void addUnit(ItemSnapshot item) {
        for (BasketLine line : lines) {
            if (line.sku().equals(item.sku())) {
                line.incrementQuantity();
                return;
            }
        }
        lines.add(new BasketLine(item.sku(), item.name(), item.price(), 1));
    }

    /** Total units scanned across all lines. */
    public int itemCount() {
        int count = 0;
        for (BasketLine line : lines) {
            count += line.quantity();
        }
        return count;
    }

    /** Basket value, rounded to the contract's two decimal places. */
    public double total() {
        double total = 0.0;
        for (BasketLine line : lines) {
            total += line.lineTotal();
        }
        return Money.round2(total);
    }

    /** Throw unless this basket is still open. */
    public void requireOpen() {
        if (status != BasketStatus.OPEN) {
            throw new TransactionNotOpenException(transactionId, status.name());
        }
    }

    /**
     * Step 1 of the checkout saga: freeze an open, non-empty basket so no scan can
     * change it while the stock step is in flight, and start a new attempt.
     *
     * <p>The empty-basket check moved here from week 4's {@code markCompleted}, which
     * ran after the decrement and relied on the rollback to undo it. A saga cannot
     * roll back another service's commit, so the check has to come before inventory
     * is ever called.
     *
     * @return the attempt number, which names this attempt's sale in the inventory ledger
     */
    public int beginCompletion() {
        requireOpen();
        if (lines.isEmpty()) {
            throw new EmptyBasketException();
        }
        this.status = BasketStatus.COMPLETING;
        return ++sagaAttempt;
    }

    /**
     * Lines in a stable SKU order. Completion walks inventory in this order so
     * two concurrent completions touching the same pair of SKUs acquire their
     * row locks in the same sequence and cannot deadlock.
     */
    public List<BasketLine> linesSortedBySku() {
        List<BasketLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(BasketLine::sku));
        return sorted;
    }

    public List<BasketLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public String transactionId() {
        return transactionId;
    }

    public String stationId() {
        return stationId;
    }

    public BasketStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public int sagaAttempt() {
        return sagaAttempt;
    }
}
