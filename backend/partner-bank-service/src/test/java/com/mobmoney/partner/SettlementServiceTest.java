package com.mobmoney.partner;

import com.mobmoney.partner.model.Settlement;
import com.mobmoney.partner.service.SettlementService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementServiceTest {

    // No latency / no injected faults for deterministic unit testing.
    private final SettlementService service =
            new SettlementService(new BigDecimal("1000"), 0.0, 0);

    private Settlement.Request request(String ref, String amount) {
        return new Settlement.Request(ref, "PARTNER-EXT-001", new BigDecimal(amount), "USD", "test");
    }

    @Test
    void acceptsSettlementWithinLimit() {
        Settlement.Response response = service.settle(request("TXN-1", "500"));
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.reference()).isEqualTo("TXN-1");
    }

    @Test
    void rejectsAmountOverLimitDeterministically() {
        assertThatThrownBy(() -> service.settle(request("TXN-2", "5000")))
                .isInstanceOf(SettlementService.SettlementRejected.class)
                .hasMessageContaining("limit");
    }

    @Test
    void isIdempotentOnReference() {
        Settlement.Response first = service.settle(request("TXN-3", "100"));
        // Even a different amount on the same reference returns the first outcome.
        Settlement.Response replay = service.settle(request("TXN-3", "999999"));
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void rejectsMissingReference() {
        assertThatThrownBy(() -> service.settle(request(null, "100")))
                .isInstanceOf(SettlementService.SettlementRejected.class);
    }
}
