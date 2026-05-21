package com.mobmoney.transfer.exception;

import org.springframework.http.HttpStatus;

/**
 * Domain exceptions carrying the HTTP status they map to. Keeping the status on
 * the exception keeps the {@link GlobalExceptionHandler} thin.
 */
public final class ApiExceptions {

    private ApiExceptions() {}

    public static class ApiException extends RuntimeException {
        private final HttpStatus status;
        public ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
        public HttpStatus getStatus() {
            return status;
        }
    }

    /** 404 — account/transaction not found. */
    public static class NotFoundException extends ApiException {
        public NotFoundException(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }

    /** 422 — request is well-formed but cannot be processed (e.g. insufficient funds). */
    public static class UnprocessableException extends ApiException {
        public UnprocessableException(String message) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
    }

    /** 400 — semantic validation that bean-validation can't express. */
    public static class BadRequestException extends ApiException {
        public BadRequestException(String message) {
            super(HttpStatus.BAD_REQUEST, message);
        }
    }

    /** 409 — idempotency key reused with a different payload. */
    public static class IdempotencyConflictException extends ApiException {
        public IdempotencyConflictException(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    /** 502 — partner bank rejected or was unreachable after retries. */
    public static class PartnerBankException extends ApiException {
        /**
         * {@code true} when the partner definitively rejected the request (a 4xx),
         * meaning the partner did NOT take the money and we may safely compensate.
         * {@code false} when the outcome is unknown (timeout / unreachable), where
         * compensating risks a double refund and the transfer must stay PENDING.
         */
        private final boolean deterministic;

        public PartnerBankException(String message, boolean deterministic) {
            super(HttpStatus.BAD_GATEWAY, message);
            this.deterministic = deterministic;
        }

        public boolean isDeterministic() {
            return deterministic;
        }
    }
}
