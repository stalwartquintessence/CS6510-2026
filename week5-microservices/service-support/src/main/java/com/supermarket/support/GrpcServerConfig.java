package com.supermarket.support;

import io.grpc.BindableService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** A service links the gRPC server with {@code @Import(GrpcServerConfig.class)}; it serves every {@link BindableService} bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerConfig {

    @Bean
    GrpcServerLifecycle grpcServer(GrpcServerProperties properties, List<BindableService> services) {
        return new GrpcServerLifecycle(properties, services);
    }
}
