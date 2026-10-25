package com.supermarket.transaction.persistence;

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
 * ({@link TransactionItemEntity}) as an owned collection, so a single repository
 * covers both the transaction and its lines.
 *
 * <p>Week 5 adds two columns for the checkout saga: {@code sagaAttempt} numbers the
 * completion attempts (each is its own sale in the inventory ledger), and
 * {@code sagaUpdatedAt} is when the status last changed, which is how the recovery
 * job tells a stuck saga from one that is merely running.
 */
@Entity
@Table(name = "transactions")
public class TransactionEntity {

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

    @Column(nullable = false)
    private int sagaAttempt;

    private Instant sagaUpdatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = jakarta.persistence.FetchType.EAGER)
    @JoinColumn(name = "tx_id", nullable = false)
    private List<TransactionItemEntity> lines = new ArrayList<>();

    protected TransactionEntity() {
        // for JPA
    }

    public TransactionEntity(String id, String stationId) {
        this.id = id;
        this.stationId = stationId;
        this.status = TransactionStatus.OPEN;
        this.total = 0.0;
        this.createdAt = Instant.now();
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

    public int getSagaAttempt() {
        return sagaAttempt;
    }

    public void setSagaAttempt(int sagaAttempt) {
        this.sagaAttempt = sagaAttempt;
    }

    public Instant getSagaUpdatedAt() {
        return sagaUpdatedAt;
    }

    public void setSagaUpdatedAt(Instant sagaUpdatedAt) {
        this.sagaUpdatedAt = sagaUpdatedAt;
    }

    public List<TransactionItemEntity> getLines() {
        return lines;
    }
}
