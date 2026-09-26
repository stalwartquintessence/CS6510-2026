package com.supermarket.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A checkout transaction at a station. Holds its basket lines
 * ({@link TransactionItem}) as an owned collection, so a single
 * {@code TransactionRepository} covers both the transaction and its lines.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false)
    private String stationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    @Column(nullable = false)
    private double total;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = jakarta.persistence.FetchType.EAGER)
    @JoinColumn(name = "tx_id", nullable = false)
    private List<TransactionItem> lines = new ArrayList<>();

    protected Transaction() {
        // for JPA
    }

    public Transaction(String id, String stationId) {
        this.id = id;
        this.stationId = stationId;
        this.status = TransactionStatus.OPEN;
        this.total = 0.0;
        this.createdAt = Instant.now();
    }

    /** Total units scanned across all lines. */
    public int itemCount() {
        int count = 0;
        for (TransactionItem line : lines) {
            count += line.getQuantity();
        }
        return count;
    }

    public String getId() {
        return id;
    }

    public String getStationId() {
        return stationId;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public double getTotal() {
        return total;
    }

    public void setTotal(double total) {
        this.total = total;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public List<TransactionItem> getLines() {
        return lines;
    }
}
