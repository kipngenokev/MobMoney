package com.mobmoney.transfer.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persisted lifecycle of a transfer attempt keyed by the client-supplied
 * Idempotency-Key.
 *
 * The key column is UNIQUE, and we INSERT a row in {@code IN_PROGRESS} state
 * BEFORE performing the transfer ("claim-first"). A concurrent replay of the
 * same key therefore collides on the unique index at the database and is
 * rejected — guaranteeing the transfer body executes at most once, not merely
 * that we avoid a duplicate insert after the fact.
 *
 * Once the transfer reaches a terminal state we store {@code responseStatus} and
 * {@code responseBody}, so any later replay returns the original outcome
 * byte-for-byte.
 *
 * {@code requestHash} (SHA-256 of the canonical request) guards against reusing
 * a key with a different payload — that yields a 409 rather than a wrong replay.
 */
@Entity
@Table(name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_key", columnNames = "idempotency_key"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /** Authenticated principal that created the key — scopes replays per user. */
    @Column(name = "username", nullable = false)
    private String username;

    /** SHA-256 of the canonical request body. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "response_status")
    private Integer responseStatus;

    // Transfer/error response JSON is small; a sized VARCHAR keeps schema
    // validation unambiguous across databases (no LOB-subtype guessing).
    @Column(name = "response_body", length = 4000)
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum Status {
        /** Claimed; transfer in flight. A replay seeing this gets 409 (retry later). */
        IN_PROGRESS,
        /** Terminal outcome recorded; replay returns the stored response. */
        COMPLETED
    }
}
