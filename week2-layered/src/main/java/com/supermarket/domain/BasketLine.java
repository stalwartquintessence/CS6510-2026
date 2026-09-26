package com.supermarket.domain;

/** One SKU's line in a basket. Repeat scans of a SKU fold into a quantity. */
public final class BasketLine {

    private final String sku;
    private final String name;
    private final double unitPrice;
    private int quantity;

    public BasketLine(String sku, String name, double unitPrice, int quantity) {
        this.sku = sku;
        this.name = name;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public String sku() {
        return sku;
    }

    public String name() {
        return name;
    }

    public double unitPrice() {
        return unitPrice;
    }

    public int quantity() {
        return quantity;
    }

    void incrementQuantity() {
        this.quantity++;
    }

    double lineTotal() {
        return unitPrice * quantity;
    }
}
