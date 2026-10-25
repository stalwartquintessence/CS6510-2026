package com.supermarket.catalog.model;

/**
 * One catalog item. Week 4's {@code ItemSnapshot} also carried the stock count,
 * because both lived in the one {@code items} table; stock now belongs to the
 * inventory service and its own database, so the catalog knows only what an
 * item <em>is</em>.
 */
public record ItemView(String sku, String name, double price) {
}
