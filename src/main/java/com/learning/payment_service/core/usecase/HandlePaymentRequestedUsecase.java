package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.service.Tracing;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.outbox.OutboxServiceRequest;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Order Service's PAYMENT_REQUESTED asks for a payment intent once stock is held (TDD §12.6). Opening it is
 * {@link CreateIntentUsecase} with the key {@code order_<orderId>}, the same key the REST path uses, so a redelivered
 * event returns the same intent; PAYMENT_INITIATED is written by that use case. A request Payment refuses for good
 * (provider rejection, order already paid) is reported as PAYMENT_FAILED so Order can end the saga; a provider
 * rejection already wrote its own PAYMENT_FAILED against the intent. Outages propagate for retry, then dead-letter.
 */
public class HandlePaymentRequestedUsecase
        implements Usecase<HandlePaymentRequestedUsecase.Request, HandlePaymentRequestedUsecase.Response> {

    /** {@code payload} is the event envelope JSON exactly as Order Service published it. */
    public record Request(String payload) implements UsecaseRequest {
    }

    public record Response(Outcome outcome) implements UsecaseResponse {
    }

    public enum Outcome { IGNORED, INTENT_OPEN, REFUSED }

    private static final String PROVIDER_REJECTED = "PAYMENT_PROVIDER_REJECTED";

    private final CreateIntentUsecase createIntent;
    private final OutboxService outbox;
    private final JsonMapper json;

    public HandlePaymentRequestedUsecase(CreateIntentUsecase createIntent, OutboxService outbox, JsonMapper json) {
        this.createIntent = createIntent;
        this.outbox = outbox;
        this.json = json;
    }

    /** @throws IllegalArgumentException when a PAYMENT_REQUESTED event is malformed: retried, then sent to the dead-letter topic */
    @Override
    public Response execute(Request request) {
        var root = json.readTree(request.payload());
        if (!"PAYMENT_REQUESTED".equals(root.path("eventType").asString(""))) {
            return new Response(Outcome.IGNORED);
        }
        try (var ignored = Tracing.continueTrace(root.path("traceId").asString(""))) {
            var data = root.path("data");
            var orderId = UUID.fromString(data.path("orderId").asString(""));
            var provider = data.path("provider").asString("");
            try {
                createIntent.execute(CreateIntentUsecaseRequest.builder()
                        .idempotencyKey("order_" + orderId)
                        .orderId(orderId)
                        .customerId(UUID.fromString(data.path("customerId").asString("")))
                        .amount(data.path("amount").decimalValue())
                        .currency(data.path("currency").asString(""))
                        .provider(provider.isEmpty() ? null : Provider.valueOf(provider))
                        .build());
                return new Response(Outcome.INTENT_OPEN);
            } catch (BusinessRuleViolationException e) {
                if (!PROVIDER_REJECTED.equals(e.getErrorCode())) {
                    var failed = new LinkedHashMap<String, Object>();
                    failed.put("orderId", orderId.toString());
                    failed.put("failureCode", e.getErrorCode());
                    failed.put("failureMessage", e.getMessage());
                    outbox.execute(OutboxServiceRequest.builder().aggregateId(orderId).eventType("PAYMENT_FAILED")
                            .data(failed).build());
                }
                return new Response(Outcome.REFUSED);
            }
        }
    }
}
