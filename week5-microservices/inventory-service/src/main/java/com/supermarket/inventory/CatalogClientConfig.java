package com.supermarket.inventory;

import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.support.rpc.StubPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The inventory service's one outgoing dependency: the catalog, for seeding. */
@Configuration(proxyBeanMethods = false)
class CatalogClientConfig {

    @Bean(destroyMethod = "close")
    StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> catalogStubs(InventoryRpcProperties properties) {
        return StubPool.connect(properties.catalog(), 1, CatalogServiceGrpc::newBlockingStub);
    }
}
