package com.supermarket.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code supermarket.analytics.*} — the hopping-window geometry. */
@ConfigurationProperties(prefix = "supermarket.analytics")
public record AnalyticsProperties(int windowSize, int slideInterval) {

    public AnalyticsProperties {
        if (windowSize <= 0) {
            windowSize = 1000;
        }
        if (slideInterval <= 0) {
            slideInterval = 500;
        }
    }
}
