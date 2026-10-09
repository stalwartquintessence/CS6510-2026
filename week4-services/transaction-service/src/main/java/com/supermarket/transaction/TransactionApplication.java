package com.supermarket.transaction;

import com.supermarket.persistence.SharedDatabaseConfig;
import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Week 4 transaction service: start, scan, complete, status. It owns the one critical
 * section in the system — completion's row-locked stock decrement — and records each
 * scan in {@code scan_log} for the analytics service to pick up.
 */
@SpringBootApplication
@Import({SharedDatabaseConfig.class, GrpcServerConfig.class})
public class TransactionApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionApplication.class, args);
    }
}
