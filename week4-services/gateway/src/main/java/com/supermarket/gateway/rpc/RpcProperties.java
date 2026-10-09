package com.supermarket.gateway.rpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code supermarket.rpc.*}: where each domain service lives.
 *
 * @param channelsPerService HTTP/2 connections opened to each service. One connection
 *                           multiplexes many calls, but a single Netty event loop then
 *                           carries all of them, so a handful are spread round-robin.
 * @param deadline           per-call deadline; a service that does not answer in time
 *                           becomes a 504 rather than a hung station
 */
@ConfigurationProperties(prefix = "supermarket.rpc")
public record RpcProperties(
        String catalog,
        String transaction,
        String inventory,
        String analytics,
        int channelsPerService,
        Duration deadline) {

    public RpcProperties {
        if (channelsPerService <= 0) {
            channelsPerService = 4;
        }
        if (deadline == null) {
            deadline = Duration.ofSeconds(10);
        }
    }
}
