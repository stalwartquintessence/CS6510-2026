package com.supermarket.persistence.jpa;

import com.supermarket.persistence.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

/** Package-private: reachable only through {@link JpaTransactionDao}. */
interface TransactionJpaRepository extends JpaRepository<Transaction, String> {
}
