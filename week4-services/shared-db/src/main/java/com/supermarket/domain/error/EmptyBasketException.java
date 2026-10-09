package com.supermarket.domain.error;

/** Completion was attempted with nothing scanned. */
public class EmptyBasketException extends DomainException {

    public EmptyBasketException() {
        super("EMPTY_BASKET", "Cannot complete an empty transaction");
    }
}
