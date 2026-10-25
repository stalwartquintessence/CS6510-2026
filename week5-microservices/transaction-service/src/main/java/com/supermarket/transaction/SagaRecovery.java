package com.supermarket.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link CheckoutSaga#recoverStuck} every {@code recoveryInterval}, on its own
 * thread. This is what makes a crash mid-saga harmless: on restart, any basket left
 * COMPLETING or COMPENSATING is compensated once it is {@code stuckAfter} old.
 *
 * <p>Phase 0: starts before the gRPC server (which is last) and stops after it, so
 * it never runs against a closed datasource.
 */
@Component
class SagaRecovery implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SagaRecovery.class);

    private final CheckoutSaga saga;
    private final SagaProperties properties;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    SagaRecovery(CheckoutSaga saga, SagaProperties properties) {
        this.saga = saga;
        this.properties = properties;
    }

    private void runOnce() {
        try {
            int recovered = saga.recoverStuck(Instant.now());
            if (recovered > 0) {
                log.info("Recovered {} stuck checkout saga(s)", recovered);
            }
        } catch (RuntimeException e) {
            // A database or network blip must not end the schedule; the next pass retries.
            log.warn("Saga recovery pass failed: {}", e.toString());
        }
    }

    @Override
    public void start() {
        long interval = properties.recoveryInterval().toMillis();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "saga-recovery"));
        executor.scheduleWithFixedDelay(this::runOnce, interval, interval, TimeUnit.MILLISECONDS);
        running = true;
        log.info("Saga recovery every {}, for sagas in flight longer than {}",
                properties.recoveryInterval(), properties.stuckAfter());
    }

    @Override
    public void stop() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
