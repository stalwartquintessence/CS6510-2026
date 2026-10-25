package com.supermarket.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code supermarket.rpc.*}: where the services this one calls live.
 *
 * @param transaction host:port of the transaction service (the scan feed)
 * @param catalog     host:port of the catalog service (item names)
 * @param deadline    per-call deadline
 */
@ConfigurationProperties(prefix = "supermarket.rpc")
public record AnalyticsRpcProperties(String transaction, String catalog, Duration deadline) {

    public AnalyticsRpcProperties {
        if (deadline == null || deadline.isNegative() || deadline.isZero()) {
            deadline = Duration.ofSeconds(5);
        }
    }
}
