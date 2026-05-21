package com.mobmoney.transfer.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test against a real MySQL (Testcontainers). Flyway
 * provisions the schema + seed users on the container. Verifies the full HTTP
 * stack: JWT auth, an internal transfer's ACID balance update, idempotent
 * replay, and that concurrent transfers from one account don't lose updates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferFlowIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mobmoney")
            .withUsername("mobmoney")
            .withPassword("mobmoney");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String login(String username, String password) {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                baseUrl() + "/api/auth/login",
                java.util.Map.of("username", username, "password", password),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("accessToken").asText();
    }

    private HttpHeaders authHeaders(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private BigDecimal balanceOf(String token, String accountNumber) {
        ResponseEntity<JsonNode> response = rest.exchange(
                baseUrl() + "/api/accounts/" + accountNumber,
                HttpMethod.GET, new HttpEntity<>(authHeaders(token, null)), JsonNode.class);
        assertThat(response.getStatusCode())
                .as("GET %s returned %s body=%s", accountNumber, response.getStatusCode(), response.getBody())
                .isEqualTo(HttpStatus.OK);
        return new BigDecimal(response.getBody().get("balance").asText());
    }

    @Test
    void unauthenticatedRequestsAreRejected() {
        ResponseEntity<String> response = rest.getForEntity(baseUrl() + "/api/accounts", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalTransferMovesMoneyAndIsIdempotent() {
        String aliceToken = login("alice", "Password123!");
        String bobToken = login("bob", "Password123!");
        // Each balance is read with the OWNING user's token (accounts are masked to others).
        BigDecimal aliceStart = balanceOf(aliceToken, "ACC-ALICE-001");
        BigDecimal bobStart = balanceOf(bobToken, "ACC-BOB-001");

        String key = UUID.randomUUID().toString();
        var body = java.util.Map.of(
                "sourceAccountNumber", "ACC-ALICE-001",
                "destinationAccountNumber", "ACC-BOB-001",
                "amount", "100.00",
                "currency", "USD",
                "narrative", "integration test");

        ResponseEntity<JsonNode> first = rest.exchange(baseUrl() + "/api/transfers",
                HttpMethod.POST, new HttpEntity<>(body, authHeaders(aliceToken, key)), JsonNode.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("status").asText()).isEqualTo("COMPLETED");
        String reference = first.getBody().get("reference").asText();

        // Balances moved by exactly 100.
        assertThat(balanceOf(aliceToken, "ACC-ALICE-001")).isEqualByComparingTo(aliceStart.subtract(new BigDecimal("100.00")));
        assertThat(balanceOf(bobToken, "ACC-BOB-001")).isEqualByComparingTo(bobStart.add(new BigDecimal("100.00")));

        // Replaying the same Idempotency-Key returns the SAME reference and does NOT move money again.
        ResponseEntity<JsonNode> replay = rest.exchange(baseUrl() + "/api/transfers",
                HttpMethod.POST, new HttpEntity<>(body, authHeaders(aliceToken, key)), JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("reference").asText()).isEqualTo(reference);
        assertThat(balanceOf(aliceToken, "ACC-ALICE-001")).isEqualByComparingTo(aliceStart.subtract(new BigDecimal("100.00")));
    }

    @Test
    void reusingKeyWithDifferentPayloadConflicts() {
        String token = login("alice", "Password123!");
        String key = UUID.randomUUID().toString();

        var body1 = java.util.Map.of("sourceAccountNumber", "ACC-ALICE-002",
                "destinationAccountNumber", "ACC-BOB-001", "amount", "10.00", "currency", "USD", "narrative", "a");
        var body2 = java.util.Map.of("sourceAccountNumber", "ACC-ALICE-002",
                "destinationAccountNumber", "ACC-BOB-001", "amount", "99.00", "currency", "USD", "narrative", "b");

        rest.exchange(baseUrl() + "/api/transfers", HttpMethod.POST,
                new HttpEntity<>(body1, authHeaders(token, key)), JsonNode.class);
        ResponseEntity<JsonNode> conflict = rest.exchange(baseUrl() + "/api/transfers", HttpMethod.POST,
                new HttpEntity<>(body2, authHeaders(token, key)), JsonNode.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void concurrentTransfersFromSameAccountDoNotLoseUpdates() throws Exception {
        String token = login("bob", "Password123!");
        // bob ACC-BOB-001 starts at 500 (less any prior test moves into it — read live).
        BigDecimal start = balanceOf(token, "ACC-BOB-001");

        int threads = 8;
        BigDecimal each = new BigDecimal("5.00");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger created = new AtomicInteger();

        Future<?>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                var body = java.util.Map.of("sourceAccountNumber", "ACC-BOB-001",
                        "destinationAccountNumber", "ACC-ALICE-001", "amount", "5.00",
                        "currency", "USD", "narrative", "concurrent");
                ResponseEntity<JsonNode> r = rest.exchange(baseUrl() + "/api/transfers", HttpMethod.POST,
                        new HttpEntity<>(body, authHeaders(token, UUID.randomUUID().toString())), JsonNode.class);
                if (r.getStatusCode() == HttpStatus.CREATED
                        && "COMPLETED".equals(r.getBody().get("status").asText())) {
                    created.incrementAndGet();
                }
            });
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        // Final balance must equal start minus (successful transfers * 5) exactly — no lost updates.
        BigDecimal expected = start.subtract(each.multiply(BigDecimal.valueOf(created.get())));
        assertThat(balanceOf(token, "ACC-BOB-001")).isEqualByComparingTo(expected);
    }
}
