package com.supermarket.analytics.model;

/** One ranked item in the current window. */
public record PopularItemView(String sku, String name, int scanCount, int rank) {
}
