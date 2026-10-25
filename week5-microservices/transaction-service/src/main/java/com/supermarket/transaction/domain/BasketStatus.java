package com.supermarket.transaction.domain;

/**
 * Lifecycle of a checkout basket.
 *
 * <p>The contract's enum is OPEN, COMPLETED, CANCELLED. Week 5 adds two states that
 * exist only while the checkout saga is running, because completing a sale now
 * spans two databases and the basket has to be frozen while the stock step is in
 * flight:
 * <pre>
 *   OPEN ─freeze─▶ COMPLETING ─stock deducted─▶ COMPLETED
 *                      │
 *                      └─stock step failed─▶ COMPENSATING ─stock restored─▶ OPEN
 * </pre>
 * Clients never see them: {@link #visible()} reports both as OPEN.
 */
public enum BasketStatus {
    OPEN, COMPLETING, COMPENSATING, COMPLETED, CANCELLED;

    /** The status as the public contract names it. */
    public BasketStatus visible() {
        return this == COMPLETING || this == COMPENSATING ? OPEN : this;
    }
}
