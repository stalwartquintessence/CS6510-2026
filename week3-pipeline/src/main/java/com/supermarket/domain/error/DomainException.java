package com.supermarket.domain.error;

/**
 * Base type for business-rule failures raised below the API layer.
 *
 * <p>Carries a machine-readable {@code errorCode} — <em>which</em> rule was
 * broken — but deliberately no HTTP status. Choosing the status code is the API
 * layer's job; see {@code com.supermarket.api.error.GlobalExceptionHandler}.
 * Week 1's equivalents were named {@code BadRequestException} /
 * {@code NotFoundException} / {@code ConflictException}, which pulled wire
 * protocol vocabulary down into the service layer.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
