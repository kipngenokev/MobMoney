package com.mobmoney.transfer.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ledger record of a transfer. Once written its monetary fields are immutable;
 * only {@code status} and {@code failureReason} transition over the lifecycle.
 */
@Entity
@Table(name = "transfer_transaction",
        indexes = {
                @Index(name = "idx_txn_reference", columnList = "reference", unique = true),
                @Index(name = "idx_txn_source", columnList = "source_account_number"),
                @Index(name = "idx_txn_created", columnList = "created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public, opaque reference returned to clients. */
    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @Column(name = "source_account_number", nullable = false, length = 34)
    private String sourceAccountNumber;

    @Column(name = "destination_account_number", nullable = false, length = 34)
    private String destinationAccountNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 140)
    private String narrative;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public enum TransferType {
        /** Both accounts internal to this service. */
        INTERNAL,
        /** Destination is held at the partner bank; settled over Camel. */
        EXTERNAL
    }

    public enum TransferStatus {
        PENDING,
        COMPLETED,
        FAILED
    }
}
