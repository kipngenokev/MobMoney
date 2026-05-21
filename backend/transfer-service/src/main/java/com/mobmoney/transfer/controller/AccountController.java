package com.mobmoney.transfer.controller;

import com.mobmoney.transfer.dto.Dtos.AccountResponse;
import com.mobmoney.transfer.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @Operation(summary = "List the authenticated user's accounts")
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt principal) {
        return accountService.listForOwner(principal.getSubject());
    }

    @GetMapping("/{accountNumber}")
    @Operation(summary = "Fetch one owned account (powers the real-time balance widget)")
    public AccountResponse get(@AuthenticationPrincipal Jwt principal, @PathVariable String accountNumber) {
        return accountService.getOwnedAccount(accountNumber, principal.getSubject());
    }
}
