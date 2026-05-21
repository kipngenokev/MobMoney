package com.mobmoney.transfer.controller;

import com.mobmoney.transfer.dto.Dtos.PageResponse;
import com.mobmoney.transfer.dto.Dtos.TransferRequest;
import com.mobmoney.transfer.dto.Dtos.TransferResponse;
import com.mobmoney.transfer.service.TransferService;
import com.mobmoney.transfer.service.TransferService.StoredResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Initiate a transfer. Requires an Idempotency-Key header; "
            + "replaying the same key returns the original outcome without moving money again.")
    public ResponseEntity<String> create(
            @AuthenticationPrincipal Jwt principal,
            @Parameter(description = "Client-generated unique key (e.g. UUID) for safe retries", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        StoredResponse result = transferService.transfer(principal.getSubject(), request, idempotencyKey);
        // Body is pre-serialized JSON so the first response and any replay are byte-identical.
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Fetch a single transfer by reference")
    public TransferResponse get(@AuthenticationPrincipal Jwt principal, @PathVariable String reference) {
        return transferService.getByReference(principal.getSubject(), reference);
    }

    @GetMapping
    @Operation(summary = "Paginated transaction history for an owned account")
    public PageResponse<TransferResponse> history(
            @AuthenticationPrincipal Jwt principal,
            @RequestParam String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transferService.history(principal.getSubject(), accountNumber, page, Math.min(size, 100));
    }
}
