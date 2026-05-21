package com.mobmoney.transfer.service;

import com.mobmoney.transfer.domain.Account;
import com.mobmoney.transfer.domain.TransferTransaction;
import com.mobmoney.transfer.domain.TransferTransaction.TransferStatus;
import com.mobmoney.transfer.dto.Dtos.TransferRequest;
import com.mobmoney.transfer.exception.ApiExceptions.UnprocessableException;
import com.mobmoney.transfer.repository.AccountRepository;
import com.mobmoney.transfer.repository.TransferTransactionRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // shared save() stub isn't hit by every test
class LedgerServiceTest {

    @Mock AccountRepository accounts;
    @Mock TransferTransactionRepository transactions;

    LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(accounts, transactions);
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Account acc(String number, String balance) {
        return Account.builder().accountNumber(number).ownerUsername("alice")
                .currency("USD").balance(new BigDecimal(balance))
                .type(Account.AccountType.INTERNAL).version(0L).build();
    }

    private TransferRequest req(String amount) {
        return new TransferRequest("ACC-A", "ACC-B", new BigDecimal(amount), "USD", "test");
    }

    @Test
    void internalTransferMovesExactAmountAndCompletes() {
        Account a = acc("ACC-A", "1000.00");
        Account b = acc("ACC-B", "100.00");
        when(accounts.findByAccountNumberForUpdate("ACC-A")).thenReturn(Optional.of(a));
        when(accounts.findByAccountNumberForUpdate("ACC-B")).thenReturn(Optional.of(b));

        TransferTransaction txn = ledger.executeInternalTransfer("TXN-1", req("250.00"));

        assertThat(a.getBalance()).isEqualByComparingTo("750.00");
        assertThat(b.getBalance()).isEqualByComparingTo("350.00");
        assertThat(txn.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(txn.getCompletedAt()).isNotNull();
        // Both accounts were locked FOR UPDATE.
        verify(accounts).findByAccountNumberForUpdate("ACC-A");
        verify(accounts).findByAccountNumberForUpdate("ACC-B");
    }

    @Test
    void internalTransferRejectsInsufficientFunds() {
        when(accounts.findByAccountNumberForUpdate("ACC-A")).thenReturn(Optional.of(acc("ACC-A", "10.00")));
        when(accounts.findByAccountNumberForUpdate("ACC-B")).thenReturn(Optional.of(acc("ACC-B", "0.00")));

        assertThatThrownBy(() -> ledger.executeInternalTransfer("TXN-1", req("250.00")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("Insufficient funds");
        verify(transactions, never()).save(any());
    }

    @Test
    void rejectsTransferToSameAccount() {
        TransferRequest sameAccount = new TransferRequest("ACC-A", "ACC-A", new BigDecimal("10"), "USD", "x");
        assertThatThrownBy(() -> ledger.executeInternalTransfer("TXN-1", sameAccount))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void externalRefundCreditsSourceBackAndMarksFailed() {
        Account source = acc("ACC-A", "750.00");
        TransferTransaction pending = TransferTransaction.builder()
                .reference("TXN-9").sourceAccountNumber("ACC-A").destinationAccountNumber("PARTNER-X")
                .amount(new BigDecimal("250.00")).currency("USD")
                .type(TransferTransaction.TransferType.EXTERNAL).status(TransferStatus.PENDING).build();
        when(transactions.findByReference("TXN-9")).thenReturn(Optional.of(pending));
        when(accounts.findByAccountNumberForUpdate("ACC-A")).thenReturn(Optional.of(source));

        TransferTransaction result = ledger.refundAndFail("TXN-9", "rejected by partner");

        assertThat(source.getBalance()).isEqualByComparingTo("1000.00"); // refunded
        assertThat(result.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.getFailureReason()).isEqualTo("rejected by partner");
    }
}
