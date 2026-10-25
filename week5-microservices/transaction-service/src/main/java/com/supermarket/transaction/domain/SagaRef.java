package com.supermarket.transaction.domain;

/**
 * A checkout saga that is in flight (COMPLETING or COMPENSATING): enough to finish
 * it without loading the basket.
 *
 * <p>Each completion attempt is its own saga with its own sale id, so a basket that
 * was reopened after a failed attempt can be completed again without colliding with
 * the tombstone the failed attempt may have left in the inventory ledger.
 */
public record SagaRef(String transactionId, int attempt, BasketStatus status) {

    public String saleId() {
        return saleId(transactionId, attempt);
    }

    public static String saleId(String transactionId, int attempt) {
        return transactionId + "/" + attempt;
    }
}
