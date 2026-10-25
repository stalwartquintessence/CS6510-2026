package com.supermarket.inventory.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Package-private: reachable only through {@link JpaSaleDao}. */
interface SaleJpaRepository extends JpaRepository<SaleEntity, String> {

    /**
     * Claims a sale id. If another transaction holds an uncommitted row for the same
     * id, PostgreSQL waits for it to finish: a commit makes this return 0, a rollback
     * lets this insert go ahead. So two steps for one sale id can never both "win".
     */
    @Modifying
    @Query(value = "INSERT INTO sales (sale_id, status, created_at, updated_at) "
            + "VALUES (:saleId, :status, now(), now()) ON CONFLICT (sale_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("saleId") String saleId, @Param("status") String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SaleEntity s where s.saleId = :saleId")
    Optional<SaleEntity> findBySaleIdForUpdate(@Param("saleId") String saleId);
}
