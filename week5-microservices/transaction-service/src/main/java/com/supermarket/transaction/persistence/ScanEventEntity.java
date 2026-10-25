package com.supermarket.transaction.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row of {@code scan_events}: the transaction service's outbox. Week 4's
 * {@code scan_log} had the same shape but sat in the shared database, where the
 * analytics service read it directly; this table is private, and analytics reads it
 * through the {@code ScanFeed} RPC.
 */
@Entity
@Table(name = "scan_events")
public class ScanEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String sku;

    @Column(nullable = false)
    private Instant scannedAt;

    protected ScanEventEntity() {
        // for JPA
    }

    public ScanEventEntity(String sku, Instant scannedAt) {
        this.sku = sku;
        this.scannedAt = scannedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }
}
