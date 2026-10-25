package com.supermarket.inventory;

import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Week 5 inventory service: owns stock in its own database. Serves low-stock reports
 * to the gateway and the saga's stock step ({@link StockLedger}) to the transaction
 * service; calls the catalog service once, to seed.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(GrpcServerConfig.class)
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
