package com.supermarket.transaction.persistence;

/**
 * Lifecycle states of a checkout transaction as stored. The contract's OPEN,
 * COMPLETED, CANCELLED, plus the two in-flight saga states (see the domain's
 * {@code BasketStatus}).
 */
public enum TransactionStatus {
    OPEN,
    COMPLETING,
    COMPENSATING,
    COMPLETED,
    CANCELLED
}
