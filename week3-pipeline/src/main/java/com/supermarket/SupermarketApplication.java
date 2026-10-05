package com.supermarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Week 3: week 2's enforced layering, with the popular-items analytics rebuilt
 * as a pipes-and-filters pipeline (Window → Rank → Enrich → Publish), each
 * filter on its own thread and connected by blocking queues. See
 * {@code com.supermarket.analytics.ScanAnalyticsPipeline}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SupermarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupermarketApplication.class, args);
    }
}
