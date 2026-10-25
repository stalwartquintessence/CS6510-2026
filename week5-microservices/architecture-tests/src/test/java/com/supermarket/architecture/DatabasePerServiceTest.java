package com.supermarket.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Each microservice has its own data store", checked against what the services are
 * actually configured to connect to: four services, four different PostgreSQL servers
 * (host:port), four different databases and four different login roles, and no
 * datasource on the gateway. ArchUnit can prove no service <em>code</em> reaches another's
 * tables; only the configuration can show that no service even holds the address of
 * another's database.
 */
class DatabasePerServiceTest {

    private static final List<String> SERVICES =
            List.of("catalog-service", "transaction-service", "inventory-service", "analytics-service");
    private static final Pattern JDBC_URL = Pattern.compile("jdbc:postgresql://([^/]+)/(\\w+)");

    @Test
    void everyServiceHasItsOwnDatabaseServerDatabaseAndRole() throws IOException {
        Map<String, String> servers = new LinkedHashMap<>();
        Map<String, String> databases = new LinkedHashMap<>();
        Map<String, String> roles = new LinkedHashMap<>();
        for (String service : SERVICES) {
            Properties properties = load(service);
            String url = properties.getProperty("spring.datasource.url");
            assertNotNull(url, service + " has no datasource");
            Matcher m = JDBC_URL.matcher(url);
            assertTrue(m.matches(), service + ": unexpected datasource URL " + url);
            servers.put(service, m.group(1));
            databases.put(service, m.group(2));
            roles.put(service, properties.getProperty("spring.datasource.username"));
        }
        assertEquals(SERVICES.size(), invert(servers).size(), "services share a database server: " + servers);
        assertEquals(SERVICES.size(), invert(databases).size(), "services share a database: " + databases);
        assertEquals(SERVICES.size(), invert(roles).size(), "services share a database role: " + roles);
    }

    @Test
    void theGatewayHasNoDatabase() throws IOException {
        assertFalse(load("gateway").stringPropertyNames().stream().anyMatch(k -> k.startsWith("spring.datasource")),
                "the gateway must not be configured with a datasource");
    }

    private static Map<String, String> invert(Map<String, String> map) {
        Map<String, String> inverted = new LinkedHashMap<>();
        map.forEach((k, v) -> inverted.put(v, k));
        return inverted;
    }

    /** Surefire runs with the module directory as the working directory, so siblings are one level up. */
    private static Properties load(String module) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("..", module, "src/main/resources/application.properties"))) {
            properties.load(reader);
        }
        return properties;
    }
}
