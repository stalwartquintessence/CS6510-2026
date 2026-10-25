package com.supermarket.support.error;

/**
 * A service this one depends on could not do its part, so this request cannot be
 * answered now; retrying later may succeed. Crosses the wire as gRPC
 * {@code UNAVAILABLE}, which the gateway answers with 503.
 *
 * <p>New in week 5: a service-based system had no service-to-service calls, so it
 * never needed to say "my dependency failed". The error code names which one
 * ({@code INVENTORY_UNAVAILABLE}, {@code CATALOG_UNAVAILABLE}, …).
 */
public class ServiceUnavailableException extends DomainException {

    public ServiceUnavailableException(String errorCode, String message) {
        super(errorCode, message);
    }
}
