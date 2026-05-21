package com.mobmoney.partner.route;

import com.mobmoney.partner.model.Settlement;
import com.mobmoney.partner.service.SettlementService;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

/**
 * Apache Camel REST route exposing the partner bank's settlement endpoint.
 *
 *   POST /partner/settlements   { reference, destinationAccountNumber, amount, currency, narrative }
 *
 * Exception mapping mirrors what the transfer service's PartnerBankClient
 * expects:
 *   - SettlementRejected   -> 422 (deterministic; client compensates)
 *   - TransientPartnerFault -> 500 (client retries)
 */
@Component
public class SettlementRoute extends RouteBuilder {

    private final SettlementService settlementService;

    public SettlementRoute(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Override
    public void configure() {
        restConfiguration()
                .component("platform-http")
                .bindingMode(RestBindingMode.json);

        onException(SettlementService.SettlementRejected.class)
                .handled(true)
                .setHeader("CamelHttpResponseCode", constant(422))
                .setBody(exchange -> new Settlement.Response(
                        referenceOf(exchange), "REJECTED",
                        exchange.getProperty("CamelExceptionCaught", Throwable.class).getMessage()));

        onException(SettlementService.TransientPartnerFault.class)
                .handled(true)
                .setHeader("CamelHttpResponseCode", constant(500))
                .setBody(exchange -> new Settlement.Response(
                        referenceOf(exchange), "ERROR",
                        exchange.getProperty("CamelExceptionCaught", Throwable.class).getMessage()));

        rest("/partner")
                .post("/settlements")
                    .type(Settlement.Request.class)
                    .outType(Settlement.Response.class)
                    .to("direct:settle");

        from("direct:settle")
                .routeId("partner-settlement")
                .log("Settlement request received: ${body}")
                // Capture the reference up front so exception handlers can echo it
                // back even after the body has been replaced by an error response.
                .setProperty("settlementReference", simple("${body.reference}"))
                .bean(settlementService, "settle")
                .setHeader("CamelHttpResponseCode", constant(200));
    }

    private String referenceOf(org.apache.camel.Exchange exchange) {
        Object ref = exchange.getProperty("settlementReference");
        return ref != null ? ref.toString() : "unknown";
    }
}
