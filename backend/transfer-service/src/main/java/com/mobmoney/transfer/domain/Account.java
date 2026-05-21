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
 * A money account. Balance is stored as DECIMAL to avoid floating-point error.
 *
 * Two layers of concurrency control protect the balance:
 *  - {@code @Version} optimistic lock (defends against lost updates if a row is
 *    read outside a pessimistic-locked path).
 *  - Pessimistic {@code SELECT ... FOR UPDATE} in the transfer path
 *    (see {@code AccountRepository#findByIdForUpdate}).
 */
@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 34)
    private String accountNumber;

    @Column(name = "owner_username", nullable = false)
    private String ownerUsername;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Current available balance. Never negative for funded accounts. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    /**
     * EXTERNAL accounts live at the partner bank; debiting/crediting them is
     * delegated over Camel rather than mutated locally.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public enum AccountType {
        INTERNAL,
        EXTERNAL
    }
}
