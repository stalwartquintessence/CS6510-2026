package com.supermarket.support.error;

/**
 * Base type for business-rule failures raised inside a service.
 *
 * <p>Carries a machine-readable {@code errorCode} — <em>which</em> rule was
 * broken — but deliberately no HTTP status. The service chassis turns it into a
 * gRPC status ({@code DomainErrorInterceptor}) and the gateway turns that into
 * HTTP. Week 1's equivalents were named {@code BadRequestException} /
 * {@code NotFoundException} / {@code ConflictException}, which pulled wire
 * protocol vocabulary down into the service layer.
 *
 * <p>Week 5: these used to live in the shared database library. With no shared
 * library left, they live in the chassis, because the error codes are part of the
 * public contract every service answers in, like the {@code .proto}.
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
