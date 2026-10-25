package com.supermarket.inventory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One SKU's live stock count in the inventory database. Week 4 kept this column on
 * the shared {@code items} row; now it is the inventory service's alone, and the
 * only way to change it is the saga's {@code DeductStock}/{@code RestoreStock}.
 */
@Entity
@Table(name = "stock_items")
public class StockItemEntity {

    @Id
    @Column(length = 32)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int stock;

    protected StockItemEntity() {
        // for JPA
    }

    public StockItemEntity(String sku, String name, int stock) {
        this.sku = sku;
        this.name = name;
        this.stock = stock;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }
}
