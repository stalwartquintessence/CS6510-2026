package com.supermarket.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code supermarket.rpc.*}: where the one service this one calls lives.
 *
 * @param catalog  host:port of the catalog service (read once, to seed stock)
 * @param deadline per-call deadline
 */
@ConfigurationProperties(prefix = "supermarket.rpc")
public record InventoryRpcProperties(String catalog, Duration deadline) {

    public InventoryRpcProperties {
        if (deadline == null || deadline.isNegative() || deadline.isZero()) {
            deadline = Duration.ofSeconds(10);
        }
    }
}
