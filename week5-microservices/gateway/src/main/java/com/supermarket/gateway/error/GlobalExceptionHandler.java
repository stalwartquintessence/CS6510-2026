package com.supermarket.gateway.error;

import com.supermarket.contracts.RpcErrors;
import com.supermarket.gateway.dto.ApiError;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one place in the system that knows about HTTP status codes — still, but now
 * with a second vocabulary to translate from. The domain services raise business-rule
 * failures as gRPC statuses plus the contract's error code (see {@link RpcErrors});
 * this maps the status to HTTP and rebuilds the same body week 3 returned.
 *
 * <p>Two outcomes are new in a distributed system and have no week 3 equivalent: a
 * service that is down (503) and one that does not answer in time (504).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<ApiError> handleRpc(StatusRuntimeException ex) {
        Status status = ex.getStatus();
        HttpStatus http;
        String fallbackCode;
        switch (status.getCode()) {
            case INVALID_ARGUMENT -> {
                http = HttpStatus.BAD_REQUEST;
                fallbackCode = "INVALID_REQUEST";
            }
            case NOT_FOUND -> {
                http = HttpStatus.NOT_FOUND;
                fallbackCode = "NOT_FOUND";
            }
            case FAILED_PRECONDITION -> {
                http = HttpStatus.CONFLICT;
                fallbackCode = "CONFLICT";
            }
            case UNAVAILABLE -> {
                http = HttpStatus.SERVICE_UNAVAILABLE;
                fallbackCode = "SERVICE_UNAVAILABLE";
            }
            case DEADLINE_EXCEEDED -> {
                http = HttpStatus.GATEWAY_TIMEOUT;
                fallbackCode = "SERVICE_TIMEOUT";
            }
            default -> {
                http = HttpStatus.INTERNAL_SERVER_ERROR;
                fallbackCode = "INTERNAL_ERROR";
            }
        }
        if (http.is5xxServerError()) {
            log.warn("Upstream call failed: {}", status);
        }
        String code = RpcErrors.errorCodeOf(ex);
        String message = status.getDescription() != null ? status.getDescription() : status.getCode().name();
        return ResponseEntity.status(http).body(new ApiError(code != null ? code : fallbackCode, message));
    }

    /** Bean-validation failures share the contract's INVALID_REQUEST code. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", ex.getMessage()));
    }
}
