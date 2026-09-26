package com.supermarket.domain;

/**
 * One ranked row of the popular-items window. Crosses downward (the analytics
 * layer hands rankings to the database access layer to persist) and upward
 * (the ranking is read back out), so like {@link ItemSnapshot} it belongs to
 * neither layer exclusively.
 */
public record PopularItemRow(String sku, String name, int scanCount, int rank) {
}
