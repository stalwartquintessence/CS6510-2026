package com.supermarket.transaction.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** Package-private: reachable only through {@link JpaScanEventDao}. */
interface ScanEventJpaRepository extends JpaRepository<ScanEventEntity, Long> {

    List<ScanEventEntity> findByIdGreaterThanOrderByIdAsc(long id, Pageable page);

    @Query("select coalesce(max(e.id), 0) from ScanEventEntity e")
    long findMaxId();
}
