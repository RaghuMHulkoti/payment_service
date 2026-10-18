package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.RefundStatus;
import com.learning.payment_service.core.domain.WebhookEvent;
import com.learning.payment_service.core.provider.WebhookHandler;
import com.learning.payment_service.core.provider.WebhookHandler.NormalizedWebhook;
import com.learning.payment_service.core.provider.WebhookHandlers;
import com.learning.payment_service.core.service.PaymentStateService;
import com.learning.payment_service.exception.ConflictException;
import com.learning.payment_service.gateway.PaymentGateway;
import com.learning.payment_service.gateway.WebhookEventGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Applies stored webhook events to intents and refunds. A webhook can arrive before the intent's provider id is
 * saved, so an event for an unknown intent is left for the next run and only given up on after
 * {@code UNKNOWN_GRACE}. A concurrent change (optimistic conflict) is also just retried next run.
 */
public class ProcessWebhookEventsUsecase
        implements Usecase<ProcessWebhookEventsUsecase.Request, ProcessWebhookEventsUsecase.Response> {

    private static final Logger log = LoggerFactory.getLogger(ProcessWebhookEventsUsecase.class);
    static final Duration UNKNOWN_GRACE = Duration.ofHours(1);

    public record Request(int limit) implements UsecaseRequest {
    }

    public record Response(int processed) implements UsecaseResponse {
    }

    private final WebhookEventGateway events;
    private final PaymentGateway gateway;
    private final WebhookHandlers handlers;
    private final PaymentStateService state;
    private final Clock clock;

    public ProcessWebhookEventsUsecase(WebhookEventGateway events, PaymentGateway gateway, WebhookHandlers handlers,
                                       PaymentStateService state, Clock clock) {
        this.events = events;
        this.gateway = gateway;
        this.handlers = handlers;
        this.state = state;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        int processed = 0;
        for (var event : events.findUnprocessed(request.limit())) {
            try {
                if (apply(event)) {
                    events.markProcessed(event.id(), now());
                    processed++;
                }
            } catch (ConflictException e) {
                log.info("Webhook {} {} hit a concurrent update; will retry", event.provider(), event.providerEventId());
            } catch (RuntimeException e) {
                log.warn("Webhook {} {} failed: {}", event.provider(), event.providerEventId(), e.getMessage());
            }
        }
        return new Response(processed);
    }

    /** Returns true when the event is finished (applied, ignored or abandoned), false to retry it later. */
    private boolean apply(WebhookEvent event) {
        NormalizedWebhook n = handlers.get(event.provider()).normalize(event.payload());
        switch (n.kind()) {
            case PAYMENT_SUCCEEDED -> {
                var intent = findIntent(event, n);
                if (intent == null) {
                    return isStale(event);
                }
                if (intent.status().isOpen()) {
                    state.markCaptured(intent);
                }
            }
            case PAYMENT_FAILED -> {
                var intent = findIntent(event, n);
                if (intent == null) {
                    return isStale(event);
                }
                if (intent.status().isOpen()) {
                    state.markFailed(intent, n.failureCode(), n.failureMessage());
                }
            }
            case PAYMENT_CANCELLED -> {
                var intent = findIntent(event, n);
                if (intent == null) {
                    return isStale(event);
                }
                if (intent.status().isOpen()) {
                    state.markCancelled(intent);
                }
            }
            case REFUND_SUCCEEDED, REFUND_FAILED -> {
                var refund = n.providerRefundId() == null ? null
                        : gateway.findRefundByProviderRefundId(n.providerRefundId()).orElse(null);
                if (refund == null) {
                    return isStale(event);
                }
                if (refund.status() == RefundStatus.INITIATED || refund.status() == RefundStatus.PROCESSING) {
                    if (n.kind() == WebhookHandler.EventKind.REFUND_SUCCEEDED) {
                        state.completeRefund(refund, n.providerRefundId());
                    } else {
                        state.failRefund(refund);
                    }
                }
            }
            case IGNORED -> {
            }
        }
        return true;
    }

    private PaymentIntent findIntent(WebhookEvent event, NormalizedWebhook n) {
        if (n.providerIntentId() == null) {
            return null;
        }
        return gateway.findIntentByProviderIntentId(event.provider(), n.providerIntentId()).orElse(null);
    }

    private boolean isStale(WebhookEvent event) {
        boolean stale = event.receivedAt().isBefore(now().minus(UNKNOWN_GRACE));
        if (stale) {
            log.warn("Dropping webhook {} {}: no matching payment after {}", event.provider(),
                    event.providerEventId(), UNKNOWN_GRACE);
        }
        return stale;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
