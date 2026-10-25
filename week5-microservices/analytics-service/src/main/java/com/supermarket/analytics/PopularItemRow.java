package com.supermarket.analytics;

/**
 * One ranked row of the popular-items window. Crosses into the persistence package
 * (the Publish stage persists rankings) and back out (the read side falls back to the
 * stored ranking), so it lives beside the stages rather than in either.
 */
public record PopularItemRow(String sku, String name, int scanCount, int rank) {
}
