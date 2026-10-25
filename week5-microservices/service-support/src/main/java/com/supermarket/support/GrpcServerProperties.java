package com.supermarket.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code supermarket.grpc.*}.
 *
 * @param port    the port this service's gRPC server listens on
 * @param threads size of the worker pool that runs service methods (blocking JDBC lives here)
 */
@ConfigurationProperties(prefix = "supermarket.grpc")
public record GrpcServerProperties(int port, int threads) {

    public GrpcServerProperties {
        if (threads <= 0) {
            threads = 32;
        }
    }
}
