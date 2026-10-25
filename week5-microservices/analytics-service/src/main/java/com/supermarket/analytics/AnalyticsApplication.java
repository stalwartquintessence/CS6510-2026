package com.supermarket.analytics;

import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Week 5 analytics service: week 3's pipes-and-filters analytics, over its own database.
 * Its input is the transaction service's scan feed (an outbox read over gRPC, see
 * {@link ScanFeedSource}) rather than week 4's shared {@code scan_log} table, and it
 * asks the catalog service for item names.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GrpcServerConfig.class)
public class AnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
