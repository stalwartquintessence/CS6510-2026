package com.supermarket.domain;

import com.supermarket.domain.error.EmptyBasketException;
import com.supermarket.domain.error.TransactionNotOpenException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The checkout aggregate: a station's basket and its lifecycle.
 *
 * <p>Deliberately free of JPA — the database access layer maps it to and from
 * {@code persistence.entity.Transaction}. That is what lets the transactions
 * layer hold the business rules (fold repeat scans, compute totals, refuse to
 * complete twice) without the upper layers ever seeing a managed entity.
 */
public final class Basket {

    private final String transactionId;
    private final String stationId;
    private final Instant startedAt;
    private final List<BasketLine> lines;
    private BasketStatus status;
    private Instant completedAt;

    public Basket(String transactionId,
                  String stationId,
                  BasketStatus status,
                  Instant startedAt,
                  Instant completedAt,
                  List<BasketLine> lines) {
        this.transactionId = transactionId;
        this.stationId = stationId;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
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

    /** Close the basket. Stock decrementing is the inventory module's job. */
    public void markCompleted(Instant when) {
        if (lines.isEmpty()) {
            throw new EmptyBasketException();
        }
        this.status = BasketStatus.COMPLETED;
        this.completedAt = when;
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
}
