package com.supermarket.transaction.domain;

/**
 * What the checkout needs to know about an item to scan it: its name and price,
 * as the catalog service reported them. Week 4 read these from the shared
 * {@code items} row (with the stock beside them); the catalog is now another
 * service's data, so this is a cached copy (see {@code CatalogCache}).
 */
public record ItemSnapshot(String sku, String name, double price) {
}
