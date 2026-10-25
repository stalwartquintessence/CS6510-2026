package com.supermarket.analytics.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single row of the most-recently-computed popular-items ranking, in the analytics
 * service's own database. The whole table is rewritten by the pipeline's Publish stage.
 */
@Entity
@Table(name = "popular_items")
public class PopularItemEntity {

    @Id
    @Column(length = 32)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int scanCount;

    @Column(name = "item_rank", nullable = false)
    private int rank;

    protected PopularItemEntity() {
        // for JPA
    }

    public PopularItemEntity(String sku, String name, int scanCount, int rank) {
        this.sku = sku;
        this.name = name;
        this.scanCount = scanCount;
        this.rank = rank;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getScanCount() {
        return scanCount;
    }

    public int getRank() {
        return rank;
    }
}
