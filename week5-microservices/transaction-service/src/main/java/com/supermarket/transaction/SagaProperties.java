package com.supermarket.transaction;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code supermarket.saga.*}: the checkout saga's timing.
 *
 * @param deductDeadline   how long the stock step may take. Must leave room inside the
 *                         gateway's 10 s deadline to notice the failure and say so.
 * @param restoreDeadline  how long one compensation attempt may take
 * @param stuckAfter       how old an in-flight saga must be before the recovery job
 *                         finishes it. Must be well past {@code deductDeadline}, so the
 *                         job never races a request that is still running.
 * @param recoveryInterval how often the recovery job looks
 */
@ConfigurationProperties(prefix = "supermarket.saga")
public record SagaProperties(Duration deductDeadline, Duration restoreDeadline, Duration stuckAfter, Duration recoveryInterval) {

    public SagaProperties {
        if (deductDeadline == null || deductDeadline.isNegative() || deductDeadline.isZero()) {
            deductDeadline = Duration.ofSeconds(9);
        }
        if (restoreDeadline == null || restoreDeadline.isNegative() || restoreDeadline.isZero()) {
            restoreDeadline = Duration.ofSeconds(5);
        }
        if (stuckAfter == null || stuckAfter.isNegative() || stuckAfter.isZero()) {
            stuckAfter = Duration.ofSeconds(30);
        }
        if (recoveryInterval == null || recoveryInterval.isNegative() || recoveryInterval.isZero()) {
            recoveryInterval = Duration.ofSeconds(5);
        }
    }
}
