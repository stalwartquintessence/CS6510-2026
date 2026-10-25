package com.supermarket.transaction;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code supermarket.rpc.*}: where the services this one calls live.
 *
 * @param catalog            host:port of the catalog service (item names and prices, cached)
 * @param inventory          host:port of the inventory service (the saga's stock step)
 * @param channelsPerService HTTP/2 channels opened to each
 * @param catalogDeadline    per-call deadline for catalog lookups
 */
@ConfigurationProperties(prefix = "supermarket.rpc")
public record TransactionRpcProperties(String catalog, String inventory, int channelsPerService, Duration catalogDeadline) {

    public TransactionRpcProperties {
        if (channelsPerService <= 0) {
            channelsPerService = 4;
        }
        if (catalogDeadline == null || catalogDeadline.isNegative() || catalogDeadline.isZero()) {
            catalogDeadline = Duration.ofSeconds(2);
        }
    }
}
