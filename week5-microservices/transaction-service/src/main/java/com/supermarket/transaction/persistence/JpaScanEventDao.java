package com.supermarket.transaction.persistence;

import com.supermarket.transaction.domain.ScanEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
class JpaScanEventDao implements ScanEventDao {

    private final ScanEventJpaRepository repository;

    JpaScanEventDao(ScanEventJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(String sku, Instant scannedAt) {
        repository.save(new ScanEventEntity(sku, scannedAt));
    }

    @Override
    public List<ScanEvent> findAfter(long afterId, int limit) {
        return repository.findByIdGreaterThanOrderByIdAsc(afterId, PageRequest.ofSize(limit)).stream()
                .map(e -> new ScanEvent(e.getId(), e.getSku(), e.getScannedAt()))
                .toList();
    }

    @Override
    public long maxId() {
        return repository.findMaxId();
    }
}
