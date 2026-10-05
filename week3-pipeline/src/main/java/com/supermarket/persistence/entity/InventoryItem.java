package com.supermarket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A catalog item plus its live stock count. One row per SKU in the {@code items}
 * table. Stock is decremented only at transaction completion, under a
 * PESSIMISTIC_WRITE row lock (see InventoryService).
 */
@Entity
@Table(name = "items")
public class InventoryItem {

    @Id
    @Column(length = 32)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private double price;

    @Column(nullable = false)
    private int stock;

    protected InventoryItem() {
        // for JPA
    }

    public InventoryItem(String sku, String name, double price, int stock) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public double getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }
}
