package com.supermarket.exception;

/**
 * Domain exceptions carrying a machine-readable {@code error} code that maps to
 * an HTTP status in {@link GlobalExceptionHandler}. Grouped here for brevity.
 */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** Base type: carries the {@code error} code echoed in the ApiError body. */
    public abstract static class ApiException extends RuntimeException {
        private final String error;

        protected ApiException(String error, String message) {
            super(message);
            this.error = error;
        }

        public String getError() {
            return error;
        }
    }

    /** 400 — the request was malformed (e.g. missing stationId/sku). */
    public static class BadRequestException extends ApiException {
        public BadRequestException(String error, String message) {
            super(error, message);
        }
    }

    /** 404 — a referenced transaction or SKU does not exist. */
    public static class NotFoundException extends ApiException {
        public NotFoundException(String error, String message) {
            super(error, message);
        }
    }

    /** 409 — the transaction is in a state that forbids the operation. */
    public static class ConflictException extends ApiException {
        public ConflictException(String error, String message) {
            super(error, message);
        }
    }
}
