package com.supermarket.inventory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * What one sale did to one SKU: units asked for and units actually taken (stock is
 * floored at zero). Ids come from a pooled sequence rather than an identity column so
 * Hibernate can batch a sale's lines into one round trip; they are written while the
 * stock row locks are held, so that round trip is on the hot path.
 */
@Entity
@Table(name = "sale_lines")
public class SaleLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sale_lines_seq")
    @SequenceGenerator(name = "sale_lines_seq", sequenceName = "sale_lines_seq", allocationSize = 50)
    private Long id;

    @Column(name = "sale_id", nullable = false, length = 64)
    private String saleId;

    @Column(nullable = false, length = 32)
    private String sku;

    @Column(nullable = false)
    private int requested;

    @Column(nullable = false)
    private int applied;

    protected SaleLineEntity() {
        // for JPA
    }

    public SaleLineEntity(String saleId, String sku, int requested, int applied) {
        this.saleId = saleId;
        this.sku = sku;
        this.requested = requested;
        this.applied = applied;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }

    public int getApplied() {
        return applied;
    }
}
