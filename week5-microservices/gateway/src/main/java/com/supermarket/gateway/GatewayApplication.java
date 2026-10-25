package com.supermarket.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The API gateway: the single HTTP entry point the load client talks to. It holds no
 * business rules and no database connection — every request becomes one gRPC call
 * on the service that owns that function.
 *
 * <p>Week 5: the routes, DTOs and error mapping are unchanged from week 4. Splitting
 * the database and adding the checkout saga happened entirely behind the four public
 * RPCs, which is the point of having a gateway. It never calls the internal
 * {@code StockService} or {@code ScanFeed}. The one change is in {@code ServiceClients}:
 * reconnecting promptly to a restarted service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
