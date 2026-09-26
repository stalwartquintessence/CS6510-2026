package com.supermarket.domain.error;

/** The referenced transaction does not exist. */
public class TransactionNotFoundException extends DomainException {

    public TransactionNotFoundException(String transactionId) {
        super("TRANSACTION_NOT_FOUND", "No such transaction: " + transactionId);
    }
}
