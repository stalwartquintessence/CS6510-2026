package com.supermarket.analytics;

import com.supermarket.persistence.SharedDatabaseConfig;
import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Week 4 analytics service: week 3's pipes-and-filters analytics, now a deployable of
 * its own. Its input is no longer an in-process method call from checkout but the
 * shared {@code scan_log} table; see {@link ScanLogSource}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import({SharedDatabaseConfig.class, GrpcServerConfig.class})
public class AnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
