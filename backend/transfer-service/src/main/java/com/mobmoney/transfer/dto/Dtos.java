package com.mobmoney.transfer.dto;

import com.mobmoney.transfer.domain.Account;
import com.mobmoney.transfer.domain.TransferTransaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request/response payloads. Records keep them immutable and concise; bean
 * validation annotations enforce input constraints at the controller edge.
 */
public final class Dtos {

    private Dtos() {}

    // ---- Auth ----

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {}

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String username) {}

    // ---- Transfer ----

    public record TransferRequest(
            @NotBlank @Size(max = 34) String sourceAccountNumber,
            @NotBlank @Size(max = 34) String destinationAccountNumber,
            @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Size(max = 140) String narrative) {}

    public record TransferResponse(
            String reference,
            String sourceAccountNumber,
            String destinationAccountNumber,
            BigDecimal amount,
            String currency,
            String type,
            String status,
            String failureReason,
            Instant createdAt,
            Instant completedAt) {

        public static TransferResponse from(TransferTransaction t) {
            return new TransferResponse(
                    t.getReference(),
                    t.getSourceAccountNumber(),
                    t.getDestinationAccountNumber(),
                    t.getAmount(),
                    t.getCurrency(),
                    t.getType().name(),
                    t.getStatus().name(),
                    t.getFailureReason(),
                    t.getCreatedAt(),
                    t.getCompletedAt());
        }
    }

    // ---- Accounts ----

    public record AccountResponse(
            String accountNumber,
            String ownerUsername,
            String currency,
            BigDecimal balance,
            String type) {

        public static AccountResponse from(Account a) {
            return new AccountResponse(
                    a.getAccountNumber(),
                    a.getOwnerUsername(),
                    a.getCurrency(),
                    a.getBalance(),
                    a.getType().name());
        }
    }

    public record PageResponse<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {}

    // ---- Errors ----

    public record ErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path) {}
}
