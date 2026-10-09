package com.supermarket.persistence.jpa;

import com.supermarket.domain.ScanEvent;
import com.supermarket.persistence.ScanLogDao;
import com.supermarket.persistence.entity.ScanLogEntry;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
class JpaScanLogDao implements ScanLogDao {

    private final ScanLogJpaRepository repository;

    JpaScanLogDao(ScanLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void append(String sku, Instant scannedAt) {
        repository.save(new ScanLogEntry(sku, scannedAt));
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
