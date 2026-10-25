package com.supermarket.catalog.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of the catalog database's {@code items} table: what an item is and costs. No stock. */
@Entity
@Table(name = "items")
public class ItemEntity {

    @Id
    @Column(length = 32)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private double price;

    protected ItemEntity() {
        // for JPA
    }

    public ItemEntity(String sku, String name, double price) {
        this.sku = sku;
        this.name = name;
        this.price = price;
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
}
