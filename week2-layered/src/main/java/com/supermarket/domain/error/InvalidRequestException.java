package com.supermarket.domain.error;

/** A required field was missing or blank. */
public class InvalidRequestException extends DomainException {

    public InvalidRequestException(String message) {
        super("INVALID_REQUEST", message);
    }
}
