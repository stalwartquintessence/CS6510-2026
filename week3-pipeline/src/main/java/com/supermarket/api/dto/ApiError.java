package com.supermarket.api.dto;

/** The contract's error body: a machine-readable code plus a human message. */
public record ApiError(String error, String message) {
}
