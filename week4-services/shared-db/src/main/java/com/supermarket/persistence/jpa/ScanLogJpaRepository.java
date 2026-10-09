package com.supermarket.persistence.jpa;

import com.supermarket.persistence.entity.ScanLogEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** Package-private: reachable only through {@link JpaScanLogDao}. */
interface ScanLogJpaRepository extends JpaRepository<ScanLogEntry, Long> {

    List<ScanLogEntry> findByIdGreaterThanOrderByIdAsc(long id, Pageable page);

    @Query("select coalesce(max(e.id), 0) from ScanLogEntry e")
    long findMaxId();
}
