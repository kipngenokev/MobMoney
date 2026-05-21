package com.mobmoney.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobmoney.transfer.client.PartnerBankClient;
import com.mobmoney.transfer.client.PartnerBankClient.SettlementRequest;
import com.mobmoney.transfer.domain.Account;
import com.mobmoney.transfer.domain.TransferTransaction;
import com.mobmoney.transfer.dto.Dtos.ErrorResponse;
import com.mobmoney.transfer.dto.Dtos.PageResponse;
import com.mobmoney.transfer.dto.Dtos.TransferRequest;
import com.mobmoney.transfer.dto.Dtos.TransferResponse;
import com.mobmoney.transfer.exception.ApiExceptions.ApiException;
import com.mobmoney.transfer.exception.ApiExceptions.BadRequestException;
import com.mobmoney.transfer.exception.ApiExceptions.NotFoundException;
import com.mobmoney.transfer.exception.ApiExceptions.PartnerBankException;
import com.mobmoney.transfer.metrics.TransferMetrics;
import com.mobmoney.transfer.repository.AccountRepository;
import com.mobmoney.transfer.repository.TransferTransactionRepository;
import com.mobmoney.transfer.service.IdempotencyService.Claimed;
import com.mobmoney.transfer.service.IdempotencyService.ClaimResult;
import com.mobmoney.transfer.service.IdempotencyService.Replay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates a transfer end-to-end: idempotency claim, routing
 * (internal vs. partner-bank), the external-settlement saga, idempotent
 * response recording, and metrics. The atomic money movements live in
 * {@link LedgerService}; this class coordinates them.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AccountRepository accounts;
    private final TransferTransactionRepository transactions;
    private final PartnerBankClient partnerBank;
    private final TransferMetrics metrics;
    private final ObjectMapper objectMapper;

    public TransferService(LedgerService ledger, IdempotencyService idempotency,
                           AccountRepository accounts, TransferTransactionRepository transactions,
                           PartnerBankClient partnerBank, TransferMetrics metrics,
                           ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.transactions = transactions;
        this.partnerBank = partnerBank;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    /** The HTTP status + JSON body to return; identical for the first call and any replay. */
    public record StoredResponse(int status, String body) {}

    public StoredResponse transfer(String username, TransferRequest req, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Missing required header: Idempotency-Key");
        }

        String requestHash = IdempotencyService.hashRequest(canonical(username, req));
        ClaimResult claim = idempotency.claim(idempotencyKey, username, requestHash);
        if (claim instanceof Replay replay) {
            metrics.recordReplay();
            log.info("Idempotent replay for key={} user={}", idempotencyKey, username);
            return new StoredResponse(replay.status(), replay.body());
        }

        Long recordId = ((Claimed) claim).recordId();
        metrics.recordInitiated();
        long start = System.nanoTime();
        try {
            StoredResponse response = process(username, req);
            idempotency.complete(recordId, response.status(), response.body());
            return response;
        } catch (ApiException businessError) {
            // Deterministic, pre-debit failure (validation/funds). No money moved —
            // record the error so replays are idempotent, then surface it.
            StoredResponse error = toErrorResponse(businessError);
            idempotency.complete(recordId, error.status(), error.body());
            metrics.recordFailed();
            return error;
        } catch (RuntimeException unexpected) {
            // Internal transfers roll back fully here (no state change) so retry is safe.
            // External flows handle their own post-debit failures inside process();
            // anything reaching here did so before money moved.
            idempotency.release(recordId);
            log.error("Unexpected error processing transfer for user={}", username, unexpected);
            throw unexpected;
        } finally {
            metrics.transferTimer().record(java.time.Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private StoredResponse process(String username, TransferRequest req) {
        Account source = accounts.findByAccountNumber(req.sourceAccountNumber())
                .orElseThrow(() -> new NotFoundException("Source account not found: " + req.sourceAccountNumber()));
        authorize(source, username);

        Account destination = accounts.findByAccountNumber(req.destinationAccountNumber())
                .orElseThrow(() -> new NotFoundException(
                        "Destination account not found: " + req.destinationAccountNumber()));

        String reference = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24).toUpperCase();

        if (destination.getType() == Account.AccountType.EXTERNAL) {
            return processExternal(reference, req);
        }
        TransferTransaction txn = ledger.executeInternalTransfer(reference, req);
        metrics.recordCompleted();
        return created(txn);
    }

    /**
     * External settlement saga:
     *   1. Debit source + write PENDING (committed) — funds reserved durably.
     *   2. Call the partner bank (idempotent on the transfer reference).
     *   3a. Accepted        -> mark COMPLETED (201).
     *   3b. Hard rejection  -> refund source + mark FAILED (201) — safe, partner took nothing.
     *   3c. Unknown outcome -> leave PENDING (202); reconciliation resolves it later.
     *       We deliberately do NOT refund on unknown outcomes to avoid double-paying
     *       if the partner actually settled but we lost the response.
     */
    private StoredResponse processExternal(String reference, TransferRequest req) {
        ledger.debitForExternalTransfer(reference, req); // throws ApiException pre-debit on validation failure

        try {
            partnerBank.settle(new SettlementRequest(
                    reference, req.destinationAccountNumber(), req.amount(), req.currency(), req.narrative()));
            TransferTransaction completed = ledger.markCompleted(reference);
            metrics.recordCompleted();
            return created(completed);
        } catch (PartnerBankException e) {
            if (e.isDeterministic()) {
                TransferTransaction failed = ledger.refundAndFail(reference, e.getMessage());
                metrics.recordCompensation();
                metrics.recordFailed();
                return created(failed);
            }
            // Unknown outcome: keep it PENDING and hand the client a 202 + reference to poll.
            log.warn("Partner outcome unknown for {}; leaving PENDING for reconciliation", reference);
            return accepted(transactions.findByReference(reference).orElseThrow());
        } catch (RuntimeException unexpected) {
            // Money already left the source and the outcome is unknown — fail closed:
            // keep PENDING, return 202, let reconciliation settle it. Never refund here.
            log.error("Unexpected error after external debit for {}; leaving PENDING", reference, unexpected);
            return accepted(transactions.findByReference(reference).orElseThrow());
        }
    }

    private void authorize(Account source, String username) {
        if (!source.getOwnerUsername().equals(username)) {
            // Mask as not-found so callers can't probe which accounts exist.
            throw new NotFoundException("Source account not found: " + source.getAccountNumber());
        }
    }

    public PageResponse<TransferResponse> history(String username, String accountNumber, int page, int size) {
        Account account = accounts.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountNumber));
        authorize(account, username);
        Page<TransferTransaction> result = transactions
                .findBySourceAccountNumberOrDestinationAccountNumberOrderByCreatedAtDesc(
                        accountNumber, accountNumber, PageRequest.of(page, size));
        return new PageResponse<>(
                result.getContent().stream().map(TransferResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public TransferResponse getByReference(String username, String reference) {
        TransferTransaction txn = transactions.findByReference(reference)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + reference));
        Account source = accounts.findByAccountNumber(txn.getSourceAccountNumber()).orElse(null);
        if (source == null || !source.getOwnerUsername().equals(username)) {
            throw new NotFoundException("Transaction not found: " + reference);
        }
        return TransferResponse.from(txn);
    }

    // ---- response helpers ----

    private StoredResponse created(TransferTransaction txn) {
        return new StoredResponse(HttpStatus.CREATED.value(), toJson(TransferResponse.from(txn)));
    }

    private StoredResponse accepted(TransferTransaction txn) {
        return new StoredResponse(HttpStatus.ACCEPTED.value(), toJson(TransferResponse.from(txn)));
    }

    private StoredResponse toErrorResponse(ApiException ex) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), ex.getStatus().value(), ex.getStatus().getReasonPhrase(),
                ex.getMessage(), "/api/transfers");
        return new StoredResponse(ex.getStatus().value(), toJson(body));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response", e);
        }
    }

    /** Stable string for hashing the request payload (key-reuse detection). */
    private String canonical(String username, TransferRequest r) {
        return String.join("|",
                username,
                r.sourceAccountNumber(),
                r.destinationAccountNumber(),
                r.amount().stripTrailingZeros().toPlainString(),
                r.currency(),
                r.narrative() == null ? "" : r.narrative());
    }
}
