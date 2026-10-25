package com.supermarket.inventory.persistence;

import com.supermarket.inventory.model.SaleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One saga step seen by the inventory service, keyed by the orchestrator's sale id.
 * The primary key is what makes {@code DeductStock} and {@code RestoreStock}
 * idempotent: whichever arrives first inserts the row, and the other finds it.
 * Rows are inserted by native {@code INSERT … ON CONFLICT DO NOTHING}; JPA only
 * reads and updates them.
 */
@Entity
@Table(name = "sales")
public class SaleEntity {

    @Id
    @Column(name = "sale_id", length = 64)
    private String saleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SaleStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SaleEntity() {
        // for JPA
    }

    public String getSaleId() {
        return saleId;
    }

    public SaleStatus getStatus() {
        return status;
    }

    public void setStatus(SaleStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }
}
