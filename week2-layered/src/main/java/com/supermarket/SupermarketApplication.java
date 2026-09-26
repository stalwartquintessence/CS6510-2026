package com.supermarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Week 2: the same self-checkout contract as week 1, restructured into four
 * explicit layers — api → transaction / analytics → persistence, over a shared
 * domain kernel. The boundaries are enforced by ArchUnit rules in
 * {@code src/test/java/com/supermarket/architecture}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SupermarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupermarketApplication.class, args);
    }
}
