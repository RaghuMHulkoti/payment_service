package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.service.Tracing;
import com.learning.payment_service.core.service.intent.cancel.CancelIntentService;
import com.learning.payment_service.core.service.intent.cancel.CancelIntentServiceRequest;
import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.RetryingProviderCall;
import com.learning.payment_service.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.UUID;

/**
 * Order Service's ORDER_CANCELLED / ORDER_FAILED (TDD §13.4) means nobody is waiting for this payment any more, so
 * every intent of that order that is still open is cancelled at the PSP and here. Without it the customer could still
 * pay with the old client secret and the money would be captured for an order that no longer exists. A capture that
 * slips through anyway (the customer paid at the same moment) is refunded by Order Service when PAYMENT_SUCCESS
 * arrives for the ended order. Redelivery is safe: an intent already cancelled or captured is skipped. A PSP outage
 * propagates so the event is retried, then dead-lettered.
 */
public class HandleOrderEndedUsecase implements Usecase<HandleOrderEndedUsecase.Request, HandleOrderEndedUsecase.Response> {

    private static final Set<String> HANDLED = Set.of("ORDER_CANCELLED", "ORDER_FAILED");
    private static final Logger log = LoggerFactory.getLogger(HandleOrderEndedUsecase.class);

    /** {@code payload} is the event envelope JSON exactly as Order Service published it. */
    public record Request(String payload) implements UsecaseRequest {
    }

    public record Response(int cancelled) implements UsecaseResponse {
    }

    private final PaymentGateway gateway;
    private final ProviderRegistry providers;
    private final RetryingProviderCall retry;
    private final CancelIntentService cancelIntent;
    private final JsonMapper json;

    public HandleOrderEndedUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry,
                                   CancelIntentService cancelIntent, JsonMapper json) {
        this.gateway = gateway;
        this.providers = providers;
        this.retry = retry;
        this.cancelIntent = cancelIntent;
        this.json = json;
    }

    /** @throws IllegalArgumentException when an ORDER_CANCELLED / ORDER_FAILED event is malformed */
    @Override
    public Response execute(Request request) {
        var root = json.readTree(request.payload());
        if (!HANDLED.contains(root.path("eventType").asString(""))) {
            return new Response(0);
        }
        try (var ignored = Tracing.continueTrace(root.path("traceId").asString(""))) {
            var orderId = UUID.fromString(root.path("data").path("orderId").asString(""));
            int cancelled = 0;
            for (var intent : gateway.findIntentsByOrder(orderId)) {
                if (!intent.status().isOpen()) {
                    continue;
                }
                if (intent.providerIntentId() != null) {
                    try {
                        var provider = providers.get(intent.provider());
                        retry.call(() -> {
                            provider.cancel(intent.providerIntentId(), intent.idempotencyKey() + ":cancel");
                            return null;
                        });
                    } catch (ProviderRejectedException e) {
                        // the PSP no longer lets it be cancelled (usually already paid): leave it to reconciliation / refund
                        log.warn("Intent {} of ended order {} could not be cancelled at the PSP: {}", intent.id(), orderId,
                                e.getMessage());
                        continue;
                    }
                }
                cancelIntent.execute(CancelIntentServiceRequest.builder().intent(intent).build());
                cancelled++;
            }
            return new Response(cancelled);
        }
    }
}
