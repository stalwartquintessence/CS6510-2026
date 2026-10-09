package com.supermarket.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One row of {@code scan_log}: a SKU scanned at a moment in time. */
@Entity
@Table(name = "scan_log")
public class ScanLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String sku;

    @Column(nullable = false)
    private Instant scannedAt;

    protected ScanLogEntry() {
        // for JPA
    }

    public ScanLogEntry(String sku, Instant scannedAt) {
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
