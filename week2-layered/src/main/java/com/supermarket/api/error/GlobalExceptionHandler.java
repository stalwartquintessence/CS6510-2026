package com.supermarket.api.error;

import com.supermarket.api.dto.ApiError;
import com.supermarket.domain.error.DomainException;
import com.supermarket.domain.error.EmptyBasketException;
import com.supermarket.domain.error.InvalidRequestException;
import com.supermarket.domain.error.ItemNotFoundException;
import com.supermarket.domain.error.TransactionNotFoundException;
import com.supermarket.domain.error.TransactionNotOpenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * The one place in the system that knows about HTTP status codes.
 *
 * <p>Layers below raise {@link DomainException}s that say <em>which rule was
 * broken</em>; this table decides what that means on the wire. Week 1 instead
 * named its exceptions {@code BadRequestException} / {@code NotFoundException}
 * / {@code ConflictException}, which pushed the wire protocol down into the
 * service layer and made the mapping implicit.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Map<Class<? extends DomainException>, HttpStatus> STATUS_BY_TYPE = Map.of(
            InvalidRequestException.class, HttpStatus.BAD_REQUEST,
            ItemNotFoundException.class, HttpStatus.NOT_FOUND,
            TransactionNotFoundException.class, HttpStatus.NOT_FOUND,
            TransactionNotOpenException.class, HttpStatus.CONFLICT,
            EmptyBasketException.class, HttpStatus.CONFLICT);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        HttpStatus status = STATUS_BY_TYPE.getOrDefault(ex.getClass(), HttpStatus.BAD_REQUEST);
        return ResponseEntity.status(status).body(new ApiError(ex.errorCode(), ex.getMessage()));
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
