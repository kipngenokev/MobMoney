package com.mobmoney.transfer.service;

import com.mobmoney.transfer.domain.IdempotencyRecord;
import com.mobmoney.transfer.exception.ApiExceptions.IdempotencyConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Implements the claim-first idempotency protocol for money-moving endpoints.
 *
 * Orchestration only — every database touch is delegated to {@link IdempotencyStore},
 * whose methods each run in their own transaction. That separation is what lets a
 * losing INSERT roll back in isolation while we still read the winning record in a
 * clean transaction (instead of a poisoned one).
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;

    public IdempotencyService(IdempotencyStore store) {
        this.store = store;
    }

    /** Outcome of attempting to claim a key. */
    public sealed interface ClaimResult permits Claimed, Replay {}

    /** We won the race and own the key; proceed with the transfer. */
    public record Claimed(Long recordId) implements ClaimResult {}

    /** A terminal response already exists for this key; return it verbatim. */
    public record Replay(int status, String body) implements ClaimResult {}

    /**
     * Attempts to claim {@code key} for {@code username}.
     *
     * @throws IdempotencyConflictException if the key was used with a different
     *         payload, or a prior attempt is still in progress.
     */
    public ClaimResult claim(String key, String username, String requestHash) {
        // Fast path: a record already exists (genuine replay or in-flight duplicate).
        Optional<IdempotencyRecord> existing = store.find(key, username);
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), requestHash);
        }
        try {
            return new Claimed(store.insertClaim(key, username, requestHash));
        } catch (DataIntegrityViolationException dup) {
            // A concurrent caller inserted between our read and insert. Their claim
            // committed; read it in a fresh transaction and resolve against it.
            IdempotencyRecord winner = store.find(key, username)
                    .orElseThrow(() -> dup); // different constraint — surface it
            return resolveExisting(winner, requestHash);
        }
    }

    private ClaimResult resolveExisting(IdempotencyRecord existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key was already used with a different request payload");
        }
        if (existing.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw new IdempotencyConflictException(
                    "A request with this Idempotency-Key is still being processed; retry shortly");
        }
        return new Replay(existing.getResponseStatus(), existing.getResponseBody());
    }

    /** Records the terminal response for a claimed key. */
    public void complete(Long recordId, int responseStatus, String responseBody) {
        store.complete(recordId, responseStatus, responseBody);
    }

    /**
     * Releases a claim so the client may retry with the same key. Used only for
     * failures where no money moved (the transfer transaction rolled back fully).
     */
    public void release(Long recordId) {
        store.release(recordId);
    }

    public Optional<IdempotencyRecord> find(String key, String username) {
        return store.find(key, username);
    }

    public static String hashRequest(String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
