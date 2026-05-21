package com.mobmoney.transfer.service;

import com.mobmoney.transfer.domain.Account;
import com.mobmoney.transfer.dto.Dtos.AccountResponse;
import com.mobmoney.transfer.exception.ApiExceptions.NotFoundException;
import com.mobmoney.transfer.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listForOwner(String username) {
        return accounts.findByOwnerUsername(username).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getOwnedAccount(String accountNumber, String username) {
        Account account = accounts.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("Account not found: " + accountNumber));
        if (!account.getOwnerUsername().equals(username)) {
            // Don't disclose existence of accounts the caller doesn't own.
            throw new NotFoundException("Account not found: " + accountNumber);
        }
        return AccountResponse.from(account);
    }
}
