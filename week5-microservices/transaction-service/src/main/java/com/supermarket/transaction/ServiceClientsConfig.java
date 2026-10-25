package com.supermarket.transaction;

import com.supermarket.contracts.CatalogServiceGrpc;
import com.supermarket.contracts.StockServiceGrpc;
import com.supermarket.support.rpc.StubPool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The transaction service's two outgoing dependencies: the catalog and the inventory's stock step. */
@Configuration(proxyBeanMethods = false)
class ServiceClientsConfig {

    @Bean(destroyMethod = "close")
    StubPool<CatalogServiceGrpc.CatalogServiceBlockingStub> catalogStubs(TransactionRpcProperties properties) {
        return StubPool.connect(properties.catalog(), properties.channelsPerService(), CatalogServiceGrpc::newBlockingStub);
    }

    @Bean(destroyMethod = "close")
    StubPool<StockServiceGrpc.StockServiceBlockingStub> stockStubs(TransactionRpcProperties properties) {
        return StubPool.connect(properties.inventory(), properties.channelsPerService(), StockServiceGrpc::newBlockingStub);
    }
}
