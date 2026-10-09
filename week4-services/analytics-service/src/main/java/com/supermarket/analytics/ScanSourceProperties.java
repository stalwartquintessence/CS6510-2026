package com.supermarket.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code supermarket.analytics.source.*} — how the scan log is tailed.
 *
 * @param pollInterval how long to sleep when the log has nothing new
 * @param settleDelay  how old an event must be before it is read (see {@link ScanLogSource})
 */
@ConfigurationProperties(prefix = "supermarket.analytics.source")
public record ScanSourceProperties(Duration pollInterval, Duration settleDelay) {

    public ScanSourceProperties {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            pollInterval = Duration.ofMillis(50);
        }
        if (settleDelay == null || settleDelay.isNegative()) {
            settleDelay = Duration.ofMillis(250);
        }
    }
}
