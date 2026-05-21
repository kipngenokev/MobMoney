package com.mobmoney.transfer.service;

import com.mobmoney.transfer.domain.IdempotencyRecord;
import com.mobmoney.transfer.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Each idempotency DB operation in its OWN physical transaction
 * ({@code REQUIRES_NEW}). This is what makes the claim visible to concurrent
 * callers immediately, and — crucially — keeps a failed claim INSERT (unique
 * violation) isolated: that transaction rolls back cleanly on its own, so the
 * subsequent "read the winner" query runs in a fresh, un-poisoned transaction.
 *
 * Lives in a separate bean from {@link IdempotencyService} so the REQUIRES_NEW
 * proxy is actually applied (Spring ignores self-invocation).
 */
@Service
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;

    public IdempotencyStore(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /** Inserts an IN_PROGRESS claim. Throws DataIntegrityViolationException on a duplicate key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insertClaim(String key, String username, String requestHash) {
        IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .username(username)
                .requestHash(requestHash)
                .status(IdempotencyRecord.Status.IN_PROGRESS)
                .build();
        // saveAndFlush forces the INSERT now so the unique-constraint race resolves here.
        return repository.saveAndFlush(record).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String key, String username) {
        return repository.findByIdempotencyKeyAndUsername(key, username);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long recordId, int responseStatus, String responseBody) {
        repository.findById(recordId).ifPresent(record -> {
            record.setStatus(IdempotencyRecord.Status.COMPLETED);
            record.setResponseStatus(responseStatus);
            record.setResponseBody(responseBody);
            repository.save(record);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long recordId) {
        repository.findById(recordId).ifPresent(record -> {
            if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
                repository.delete(record);
            }
        });
    }
}
