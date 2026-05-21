package com.mobmoney.transfer.service;

import com.mobmoney.transfer.domain.Account;
import com.mobmoney.transfer.domain.TransferTransaction;
import com.mobmoney.transfer.domain.TransferTransaction.TransferStatus;
import com.mobmoney.transfer.domain.TransferTransaction.TransferType;
import com.mobmoney.transfer.dto.Dtos.TransferRequest;
import com.mobmoney.transfer.exception.ApiExceptions.NotFoundException;
import com.mobmoney.transfer.exception.ApiExceptions.UnprocessableException;
import com.mobmoney.transfer.repository.AccountRepository;
import com.mobmoney.transfer.repository.TransferTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Owns the ACID money-movement transactions. Each public method is a single DB
 * transaction; locks are acquired with {@code SELECT ... FOR UPDATE} in a
 * deterministic order (lower account number first) so concurrent transfers that
 * touch the same pair of accounts can never deadlock by grabbing locks in
 * opposite orders.
 *
 * Kept separate from {@link TransferService} so the orchestrator can call these
 * across distinct physical transactions (required for the external-settlement
 * saga) — Spring's transactional proxy would otherwise be bypassed on
 * self-invocation.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final TransferTransactionRepository transactions;

    public LedgerService(AccountRepository accounts, TransferTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /**
     * Atomic internal transfer: debit source, credit destination, write a
     * COMPLETED ledger row — all or nothing. SERIALIZABLE-equivalent safety comes
     * from the row locks; we use the default isolation plus pessimistic locks.
     */
    @Transactional
    public TransferTransaction executeInternalTransfer(String reference, TransferRequest req) {
        // Lock in a stable global order to avoid deadlocks under concurrency.
        LockedPair pair = lockPair(req.sourceAccountNumber(), req.destinationAccountNumber());
        Account source = pair.of(req.sourceAccountNumber());
        Account destination = pair.of(req.destinationAccountNumber());

        validateTransferable(source, destination, req);

        source.setBalance(source.getBalance().subtract(req.amount()));
        destination.setBalance(destination.getBalance().add(req.amount()));
        accounts.save(source);
        accounts.save(destination);

        TransferTransaction txn = newTransaction(reference, req, TransferType.INTERNAL);
        txn.setStatus(TransferStatus.COMPLETED);
        txn.setCompletedAt(Instant.now());
        return transactions.save(txn);
    }

    /**
     * First leg of an external transfer: lock + debit the source and write a
     * PENDING ledger row. Committed before the partner is called so the funds are
     * reserved and the attempt is durably recorded.
     */
    @Transactional
    public TransferTransaction debitForExternalTransfer(String reference, TransferRequest req) {
        Account source = accounts.findByAccountNumberForUpdate(req.sourceAccountNumber())
                .orElseThrow(() -> new NotFoundException("Source account not found: " + req.sourceAccountNumber()));

        if (!source.getCurrency().equals(req.currency())) {
            throw new UnprocessableException("Currency mismatch with source account");
        }
        requireSufficientFunds(source, req.amount());

        source.setBalance(source.getBalance().subtract(req.amount()));
        accounts.save(source);

        TransferTransaction txn = newTransaction(reference, req, TransferType.EXTERNAL);
        txn.setStatus(TransferStatus.PENDING);
        return transactions.save(txn);
    }

    /** Marks a pending external transfer COMPLETED after partner acceptance. */
    @Transactional
    public TransferTransaction markCompleted(String reference) {
        TransferTransaction txn = requireTransaction(reference);
        txn.setStatus(TransferStatus.COMPLETED);
        txn.setCompletedAt(Instant.now());
        return transactions.save(txn);
    }

    /**
     * Compensating action: refund the previously-debited source and mark the
     * transfer FAILED. Only invoked when the partner DEFINITIVELY rejected, so the
     * refund cannot double-credit.
     */
    @Transactional
    public TransferTransaction refundAndFail(String reference, String reason) {
        TransferTransaction txn = requireTransaction(reference);
        Account source = accounts.findByAccountNumberForUpdate(txn.getSourceAccountNumber())
                .orElseThrow(() -> new NotFoundException("Source account vanished: " + txn.getSourceAccountNumber()));
        source.setBalance(source.getBalance().add(txn.getAmount()));
        accounts.save(source);

        txn.setStatus(TransferStatus.FAILED);
        txn.setFailureReason(truncate(reason));
        txn.setCompletedAt(Instant.now());
        return transactions.save(txn);
    }

    // ---- helpers ----

    private TransferTransaction requireTransaction(String reference) {
        return transactions.findByReference(reference)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + reference));
    }

    private TransferTransaction newTransaction(String reference, TransferRequest req, TransferType type) {
        return TransferTransaction.builder()
                .reference(reference)
                .sourceAccountNumber(req.sourceAccountNumber())
                .destinationAccountNumber(req.destinationAccountNumber())
                .amount(req.amount())
                .currency(req.currency())
                .narrative(req.narrative())
                .type(type)
                .build();
    }

    private void validateTransferable(Account source, Account destination, TransferRequest req) {
        if (!source.getCurrency().equals(req.currency()) || !destination.getCurrency().equals(req.currency())) {
            throw new UnprocessableException("Currency mismatch between accounts and request");
        }
        requireSufficientFunds(source, req.amount());
    }

    private void requireSufficientFunds(Account source, BigDecimal amount) {
        if (source.getBalance().compareTo(amount) < 0) {
            throw new UnprocessableException("Insufficient funds in account " + source.getAccountNumber());
        }
    }

    /** Locks both accounts in a deterministic order to prevent deadlocks. */
    private LockedPair lockPair(String a, String b) {
        if (a.equals(b)) {
            throw new UnprocessableException("Source and destination accounts must differ");
        }
        String first = a.compareTo(b) <= 0 ? a : b;
        String second = first.equals(a) ? b : a;
        Account firstAcct = accounts.findByAccountNumberForUpdate(first)
                .orElseThrow(() -> new NotFoundException("Account not found: " + first));
        Account secondAcct = accounts.findByAccountNumberForUpdate(second)
                .orElseThrow(() -> new NotFoundException("Account not found: " + second));
        return new LockedPair(firstAcct, secondAcct);
    }

    private record LockedPair(Account first, Account second) {
        Account of(String accountNumber) {
            if (first.getAccountNumber().equals(accountNumber)) return first;
            if (second.getAccountNumber().equals(accountNumber)) return second;
            throw new IllegalStateException("Account not in locked pair: " + accountNumber);
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
