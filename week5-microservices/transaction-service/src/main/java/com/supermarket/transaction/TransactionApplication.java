package com.supermarket.transaction;

import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Week 5 transaction service: start, scan, complete, status, over its own database.
 * It orchestrates the checkout saga ({@link CheckoutSaga}) against the inventory
 * service, caches the catalog it scans against, and publishes every scan through the
 * {@code scan_events} outbox ({@link ScanFeedService}) for the analytics service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GrpcServerConfig.class)
public class TransactionApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionApplication.class, args);
    }
}
