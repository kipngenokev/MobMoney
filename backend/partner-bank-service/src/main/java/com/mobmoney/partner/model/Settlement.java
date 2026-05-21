package com.mobmoney.partner.model;

import java.math.BigDecimal;

/** Settlement payloads exchanged with the transfer service. */
public final class Settlement {

    private Settlement() {}

    public record Request(
            String reference,
            String destinationAccountNumber,
            BigDecimal amount,
            String currency,
            String narrative) {}

    public record Response(
            String reference,
            String status,   // ACCEPTED | REJECTED
            String message) {}
}
