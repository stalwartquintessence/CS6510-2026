package com.supermarket.analytics;

import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.ScanFeedGrpc;
import com.supermarket.support.rpc.StubPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The analytics service's two outgoing dependencies: the scan feed and the catalog. */
@Configuration(proxyBeanMethods = false)
class ServiceClientsConfig {

    @Bean(destroyMethod = "close")
    StubPool<ScanFeedGrpc.ScanFeedBlockingStub> scanFeedStubs(AnalyticsRpcProperties properties) {
        return StubPool.connect(properties.transaction(), 1, ScanFeedGrpc::newBlockingStub);
    }

    @Bean(destroyMethod = "close")
    StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> catalogStubs(AnalyticsRpcProperties properties) {
        return StubPool.connect(properties.catalog(), 1, CatalogServiceGrpc::newBlockingStub);
    }
}
