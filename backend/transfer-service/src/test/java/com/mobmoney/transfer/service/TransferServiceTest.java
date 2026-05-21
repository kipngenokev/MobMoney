package com.mobmoney.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mobmoney.transfer.client.PartnerBankClient;
import com.mobmoney.transfer.client.PartnerBankClient.SettlementResponse;
import com.mobmoney.transfer.domain.Account;
import com.mobmoney.transfer.domain.TransferTransaction;
import com.mobmoney.transfer.domain.TransferTransaction.TransferStatus;
import com.mobmoney.transfer.domain.TransferTransaction.TransferType;
import com.mobmoney.transfer.dto.Dtos.TransferRequest;
import com.mobmoney.transfer.exception.ApiExceptions.PartnerBankException;
import com.mobmoney.transfer.exception.ApiExceptions.UnprocessableException;
import com.mobmoney.transfer.metrics.TransferMetrics;
import com.mobmoney.transfer.repository.AccountRepository;
import com.mobmoney.transfer.repository.TransferTransactionRepository;
import com.mobmoney.transfer.service.IdempotencyService.Claimed;
import com.mobmoney.transfer.service.IdempotencyService.Replay;
import com.mobmoney.transfer.service.TransferService.StoredResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferServiceTest {

    @Mock LedgerService ledger;
    @Mock IdempotencyService idempotency;
    @Mock AccountRepository accounts;
    @Mock TransferTransactionRepository transactions;
    @Mock PartnerBankClient partnerBank;

    TransferService service;

    private static final String USER = "alice";
    private static final String KEY = "key-123";

    @BeforeEach
    void setUp() {
        // findAndRegisterModules() pulls in JSR-310 so Instant fields serialize,
        // matching the ObjectMapper Spring Boot injects at runtime.
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TransferService(ledger, idempotency, accounts, transactions, partnerBank,
                new TransferMetrics(new SimpleMeterRegistry()), objectMapper);
    }

    private TransferRequest internalReq() {
        return new TransferRequest("ACC-A", "ACC-B", new BigDecimal("100.00"), "USD", "rent");
    }

    private TransferRequest externalReq() {
        return new TransferRequest("ACC-A", "PARTNER-X", new BigDecimal("100.00"), "USD", "payout");
    }

    private Account account(String number, String owner, Account.AccountType type) {
        return Account.builder().accountNumber(number).ownerUsername(owner)
                .currency("USD").balance(new BigDecimal("1000")).type(type).version(0L).build();
    }

    private TransferTransaction txn(TransferType type, TransferStatus status) {
        return TransferTransaction.builder().reference("TXN-1")
                .sourceAccountNumber("ACC-A").destinationAccountNumber("ACC-B")
                .amount(new BigDecimal("100.00")).currency("USD").type(type).status(status).build();
    }

    @Test
    void rejectsMissingIdempotencyKey() {
        assertThatThrownByMissingKey(null);
        assertThatThrownByMissingKey("  ");
    }

    private void assertThatThrownByMissingKey(String key) {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.transfer(USER, internalReq(), key));
    }

    @Test
    void replaysStoredResponseWithoutMovingMoney() {
        when(idempotency.claim(eq(KEY), eq(USER), anyString()))
                .thenReturn(new Replay(201, "{\"reference\":\"TXN-1\"}"));

        StoredResponse result = service.transfer(USER, internalReq(), KEY);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).contains("TXN-1");
        verifyNoInteractions(ledger);
        verify(idempotency, never()).complete(anyLong(), anyInt(), anyString());
    }

    @Test
    void internalTransferSucceedsAndRecordsResponse() {
        when(idempotency.claim(eq(KEY), eq(USER), anyString())).thenReturn(new Claimed(7L));
        when(accounts.findByAccountNumber("ACC-A")).thenReturn(Optional.of(account("ACC-A", USER, Account.AccountType.INTERNAL)));
        when(accounts.findByAccountNumber("ACC-B")).thenReturn(Optional.of(account("ACC-B", "bob", Account.AccountType.INTERNAL)));
        when(ledger.executeInternalTransfer(anyString(), any())).thenReturn(txn(TransferType.INTERNAL, TransferStatus.COMPLETED));

        StoredResponse result = service.transfer(USER, internalReq(), KEY);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).contains("COMPLETED");
        verify(idempotency).complete(eq(7L), eq(201), contains("COMPLETED"));
    }

    @Test
    void businessFailureIsRecordedAsIdempotentError() {
        when(idempotency.claim(eq(KEY), eq(USER), anyString())).thenReturn(new Claimed(7L));
        when(accounts.findByAccountNumber("ACC-A")).thenReturn(Optional.of(account("ACC-A", USER, Account.AccountType.INTERNAL)));
        when(accounts.findByAccountNumber("ACC-B")).thenReturn(Optional.of(account("ACC-B", "bob", Account.AccountType.INTERNAL)));
        when(ledger.executeInternalTransfer(anyString(), any()))
                .thenThrow(new UnprocessableException("Insufficient funds in account ACC-A"));

        StoredResponse result = service.transfer(USER, internalReq(), KEY);

        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body()).contains("Insufficient funds");
        // Recorded so a replay returns the same 422 rather than re-attempting.
        verify(idempotency).complete(eq(7L), eq(422), contains("Insufficient funds"));
        verify(idempotency, never()).release(anyLong());
    }

    @Test
    void deniesTransferFromAccountNotOwnedByCaller() {
        when(idempotency.claim(eq(KEY), eq(USER), anyString())).thenReturn(new Claimed(7L));
        when(accounts.findByAccountNumber("ACC-A")).thenReturn(Optional.of(account("ACC-A", "bob", Account.AccountType.INTERNAL)));

        StoredResponse result = service.transfer(USER, internalReq(), KEY);

        assertThat(result.status()).isEqualTo(404); // masked as not-found
        verify(ledger, never()).executeInternalTransfer(anyString(), any());
    }

    @Test
    void externalTransferSettlesViaPartner() {
        when(idempotency.claim(eq(KEY), eq(USER), anyString())).thenReturn(new Claimed(7L));
        when(accounts.findByAccountNumber("ACC-A")).thenReturn(Optional.of(account("ACC-A", USER, Account.AccountType.INTERNAL)));
        when(accounts.findByAccountNumber("PARTNER-X")).thenReturn(Optional.of(account("PARTNER-X", USER, Account.AccountType.EXTERNAL)));
        when(ledger.debitForExternalTransfer(anyString(), any())).thenReturn(txn(TransferType.EXTERNAL, TransferStatus.PENDING));
        when(partnerBank.settle(any())).thenReturn(new SettlementResponse("TXN-1", "ACCEPTED", "ok"));
        when(ledger.markCompleted(anyString())).thenReturn(txn(TransferType.EXTERNAL, TransferStatus.COMPLETED));

        StoredResponse result = service.transfer(USER, externalReq(), KEY);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).contains("COMPLETED");
        verify(ledger).markCompleted(anyString());
        verify(ledger, never()).refundAndFail(anyString(), anyString());
    }

    @Test
    void externalDeterministicRejectionCompensatesSource() {
        when(idempotency.claim(eq(KEY), eq(USER), anyString())).thenReturn(new Claimed(7L));
        when(accounts.findByAccountNumber("ACC-A")).thenReturn(Optional.of(account("ACC-A", USER, Account.AccountType.INTERNAL)));
        when(accounts.findByAccountNumber("PARTNER-X")).thenReturn(Optional.of(account("PARTNER-X", USER, Account.AccountType.EXTERNAL)));
        when(ledger.debitForExternalTransfer(anyString(), any())).thenReturn(txn(TransferType.EXTERNAL, TransferStatus.PENDING));
        when(partnerBank.settle(any())).thenThrow(new PartnerBankException("limit exceeded", true));
        when(ledger.refundAndFail(anyString(), anyString())).thenReturn(txn(TransferType.EXTERNAL, TransferStatus.FAILED));

        StoredResponse result = service.transfer(USER, externalReq(), KEY);

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).contains("FAILED");
        verify(ledger).refundAndFail(anyString(), eq("limit exceeded"));
    }

    @Test
    void externalUnknownOutcomeLeavesTransferPending() {
        when(idempotency.claim(eq(KEY), eq(USER), anyString())).thenReturn(new Claimed(7L));
        when(accounts.findByAccountNumber("ACC-A")).thenReturn(Optional.of(account("ACC-A", USER, Account.AccountType.INTERNAL)));
        when(accounts.findByAccountNumber("PARTNER-X")).thenReturn(Optional.of(account("PARTNER-X", USER, Account.AccountType.EXTERNAL)));
        when(ledger.debitForExternalTransfer(anyString(), any())).thenReturn(txn(TransferType.EXTERNAL, TransferStatus.PENDING));
        when(partnerBank.settle(any())).thenThrow(new PartnerBankException("timeout", false));
        when(transactions.findByReference(anyString())).thenReturn(Optional.of(txn(TransferType.EXTERNAL, TransferStatus.PENDING)));

        StoredResponse result = service.transfer(USER, externalReq(), KEY);

        assertThat(result.status()).isEqualTo(202); // Accepted, still pending
        assertThat(result.body()).contains("PENDING");
        // Never compensate on an unknown outcome.
        verify(ledger, never()).refundAndFail(anyString(), anyString());
    }
}
