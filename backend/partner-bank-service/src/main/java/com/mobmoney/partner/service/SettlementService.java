package com.mobmoney.partner.service;

import com.mobmoney.partner.model.Settlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a partner bank's settlement engine. Real partners are slow,
 * occasionally flaky, and idempotent — this models all three so the transfer
 * service's saga / retry / compensation logic can be exercised end-to-end.
 *
 *  - Idempotent on {@code reference}: a replay returns the first stored outcome,
 *    so the transfer service can safely retry an unknown-outcome call.
 *  - Deterministic rejection: amounts above a configurable ceiling are REJECTED
 *    (4xx upstream), letting the transfer service safely compensate.
 *  - Injected latency + transient faults: configurable probability of a slow
 *    response or a 500, to exercise retries.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final Map<String, Settlement.Response> ledger = new ConcurrentHashMap<>();

    private final BigDecimal rejectAbove;
    private final double transientFaultProbability;
    private final long maxLatencyMs;

    public SettlementService(
            @Value("${partner.reject-above:1000000}") BigDecimal rejectAbove,
            @Value("${partner.transient-fault-probability:0.0}") double transientFaultProbability,
            @Value("${partner.max-latency-ms:200}") long maxLatencyMs) {
        this.rejectAbove = rejectAbove;
        this.transientFaultProbability = transientFaultProbability;
        this.maxLatencyMs = maxLatencyMs;
    }

    /** Thrown for injected transient faults; mapped to HTTP 500 by the route. */
    public static class TransientPartnerFault extends RuntimeException {
        public TransientPartnerFault(String message) {
            super(message);
        }
    }

    /** Thrown for deterministic rejections; mapped to HTTP 422 by the route. */
    public static class SettlementRejected extends RuntimeException {
        public SettlementRejected(String message) {
            super(message);
        }
    }

    public Settlement.Response settle(Settlement.Request request) {
        if (request == null || request.reference() == null || request.reference().isBlank()) {
            throw new SettlementRejected("Missing settlement reference");
        }

        // Idempotency: identical reference returns the first recorded outcome.
        Settlement.Response existing = ledger.get(request.reference());
        if (existing != null) {
            log.info("Idempotent settlement replay for reference={}", request.reference());
            return existing;
        }

        simulateLatency();
        maybeInjectTransientFault(request.reference());

        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new SettlementRejected("Amount must be positive");
        }
        if (request.amount().compareTo(rejectAbove) > 0) {
            throw new SettlementRejected("Amount exceeds partner settlement limit");
        }

        Settlement.Response accepted = new Settlement.Response(
                request.reference(), "ACCEPTED", "Settled at partner bank");
        // putIfAbsent so a concurrent duplicate still resolves to one outcome.
        Settlement.Response stored = ledger.putIfAbsent(request.reference(), accepted);
        Settlement.Response result = stored != null ? stored : accepted;
        log.info("Settlement {} for reference={} amount={}",
                result.status(), request.reference(), request.amount());
        return result;
    }

    private void simulateLatency() {
        if (maxLatencyMs <= 0) return;
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(maxLatencyMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void maybeInjectTransientFault(String reference) {
        if (transientFaultProbability > 0
                && ThreadLocalRandom.current().nextDouble() < transientFaultProbability) {
            log.warn("Injecting transient fault for reference={}", reference);
            throw new TransientPartnerFault("Simulated partner outage");
        }
    }
}
