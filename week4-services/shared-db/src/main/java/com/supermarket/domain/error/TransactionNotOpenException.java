package com.supermarket.domain.error;

/** The transaction has already been completed or cancelled. */
public class TransactionNotOpenException extends DomainException {

    public TransactionNotOpenException(String transactionId, String status) {
        super("TRANSACTION_NOT_OPEN", "Transaction " + transactionId + " is " + status);
    }
}
