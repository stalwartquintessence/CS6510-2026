package com.supermarket.inventory;

import com.supermarket.persistence.SharedDatabaseConfig;
import com.supermarket.support.GrpcServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/** Week 4 inventory service: stock reporting. Reads {@code items} through the shared {@code ItemDao}. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import({SharedDatabaseConfig.class, GrpcServerConfig.class})
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
