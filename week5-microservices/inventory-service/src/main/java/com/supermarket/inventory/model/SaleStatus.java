package com.supermarket.inventory.model;

/**
 * A sale's state in the inventory ledger. {@code REVERTED} is reached either by
 * compensating an applied sale or, as a tombstone, by a restore that arrived before
 * (or instead of) its deduct.
 */
public enum SaleStatus {
    APPLIED, REVERTED
}
