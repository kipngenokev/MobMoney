package com.mobmoney.transfer.client;

import com.mobmoney.transfer.exception.ApiExceptions.PartnerBankException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;

/**
 * Talks to the partner-bank-service (an Apache Camel app simulating a partner
 * institution). The remote call is the boundary of our transactional control,
 * so this client is built defensively:
 *
 *  - a bounded number of retries on connection/5xx errors (the partner endpoint
 *    is idempotent on {@code reference}, so retrying is safe);
 *  - distinct exceptions for "definitely rejected" vs "unknown" so the caller
 *    can decide whether to compensate or leave the transfer pending.
 */
@Component
public class PartnerBankClient {

    private static final Logger log = LoggerFactory.getLogger(PartnerBankClient.class);

    private final RestClient restClient;
    private final int maxAttempts;

    public PartnerBankClient(RestClient.Builder builder,
                             @Value("${partner-bank.base-url:http://localhost:8081}") String baseUrl,
                             @Value("${partner-bank.max-attempts:3}") int maxAttempts) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.maxAttempts = maxAttempts;
    }

    public record SettlementRequest(String reference, String destinationAccountNumber,
                                    BigDecimal amount, String currency, String narrative) {}

    public record SettlementResponse(String reference, String status, String message) {}

    /**
     * Requests settlement of an outbound transfer at the partner bank.
     *
     * @return the partner's accepted settlement response
     * @throws PartnerBankException if the partner explicitly rejected (4xx,
     *         deterministic) or remained unreachable after all retries.
     */
    public SettlementResponse settle(SettlementRequest request) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient.post()
                        .uri("/partner/settlements")
                        // Reuse the transfer reference as the partner-side idempotency key.
                        .header("Idempotency-Key", request.reference())
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                            throw new PartnerBankException(
                                    "Partner bank rejected settlement: HTTP " + res.getStatusCode(), true);
                        })
                        .body(SettlementResponse.class);
            } catch (PartnerBankException rejected) {
                throw rejected; // deterministic rejection — do not retry
            } catch (ResourceAccessException | org.springframework.web.client.HttpServerErrorException transient_) {
                last = transient_;
                log.warn("Partner bank call failed (attempt {}/{}): {}", attempt, maxAttempts,
                        transient_.getMessage());
                sleepBackoff(attempt);
            }
        }
        throw new PartnerBankException("Partner bank unreachable after " + maxAttempts + " attempts: "
                + (last != null ? last.getMessage() : "unknown"), false);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(1000L * attempt, 3000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
