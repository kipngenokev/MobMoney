package com.mobmoney.transfer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Domain metrics exported to Prometheus via Micrometer. These sit alongside the
 * JVM/HTTP metrics Actuator provides out of the box and power the Grafana
 * dashboard's business panels.
 */
@Component
public class TransferMetrics {

    private final Counter initiated;
    private final Counter completed;
    private final Counter failed;
    private final Counter idempotentReplays;
    private final Counter compensations;
    private final Timer transferTimer;

    public TransferMetrics(MeterRegistry registry) {
        this.initiated = Counter.builder("mobmoney_transfers_initiated_total")
                .description("Total transfers initiated").register(registry);
        this.completed = Counter.builder("mobmoney_transfers_completed_total")
                .description("Total transfers completed successfully").register(registry);
        this.failed = Counter.builder("mobmoney_transfers_failed_total")
                .description("Total transfers that ended FAILED").register(registry);
        this.idempotentReplays = Counter.builder("mobmoney_idempotent_replays_total")
                .description("Requests served from a stored idempotent response").register(registry);
        this.compensations = Counter.builder("mobmoney_compensations_total")
                .description("Source-account refunds issued after partner failure").register(registry);
        this.transferTimer = Timer.builder("mobmoney_transfer_duration")
                .description("End-to-end transfer processing time").register(registry);
    }

    public void recordInitiated() { initiated.increment(); }
    public void recordCompleted() { completed.increment(); }
    public void recordFailed() { failed.increment(); }
    public void recordReplay() { idempotentReplays.increment(); }
    public void recordCompensation() { compensations.increment(); }
    public Timer transferTimer() { return transferTimer; }
}
