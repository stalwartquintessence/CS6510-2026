package com.supermarket.persistence.entity;

/** Lifecycle states of a checkout transaction (matches the OpenAPI enum). */
public enum TransactionStatus {
    OPEN,
    COMPLETED,
    CANCELLED
}
