package com.supermarket.domain;

/**
 * An immutable read of one catalog/inventory row, as it crosses a layer
 * boundary. Lives in {@code domain} rather than in the transactions layer
 * because it is also passed <em>down</em> into the database access layer
 * (seeding), and a downward-crossing type owned by an upper layer would invert
 * the dependency direction the architecture is built on.
 */
public record ItemSnapshot(String sku, String name, double price, int stock) {
}
