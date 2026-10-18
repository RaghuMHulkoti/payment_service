package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.service.intent.cancel.CancelIntentService;
import com.learning.payment_service.core.service.intent.cancel.CancelIntentServiceRequest;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentService;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentServiceRequest;
import com.learning.payment_service.core.service.refund.complete.CompleteRefundService;
import com.learning.payment_service.core.service.refund.complete.CompleteRefundServiceRequest;
import com.learning.payment_service.core.service.intent.fail.FailIntentService;
import com.learning.payment_service.core.service.intent.fail.FailIntentServiceRequest;
import com.learning.payment_service.core.service.refund.fail.FailRefundService;
import com.learning.payment_service.core.service.refund.fail.FailRefundServiceRequest;
import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.RefundStatus;
import com.learning.payment_service.core.domain.WebhookEvent;
import com.learning.payment_service.core.provider.WebhookHandler;
import com.learning.payment_service.core.provider.WebhookHandler.NormalizedWebhook;
import com.learning.payment_service.core.provider.WebhookHandlers;
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
        implements Usecase<ProcessWebhookEventsUsecaseRequest, ProcessWebhookEventsUsecaseResponse> {

    private static final Logger log = LoggerFactory.getLogger(ProcessWebhookEventsUsecase.class);
    static final Duration UNKNOWN_GRACE = Duration.ofHours(1);



    private final WebhookEventGateway events;
    private final PaymentGateway gateway;
    private final WebhookHandlers handlers;
    private final CancelIntentService cancelIntentService;
    private final CaptureIntentService captureIntentService;
    private final CompleteRefundService completeRefundService;
    private final FailIntentService failIntentService;
    private final FailRefundService failRefundService;
    private final Clock clock;

    public ProcessWebhookEventsUsecase(WebhookEventGateway events, PaymentGateway gateway, WebhookHandlers handlers, CancelIntentService cancelIntentService, CaptureIntentService captureIntentService, CompleteRefundService completeRefundService, FailIntentService failIntentService, FailRefundService failRefundService, Clock clock) {
        this.events = events;
        this.gateway = gateway;
        this.handlers = handlers;
        this.cancelIntentService = cancelIntentService;
        this.captureIntentService = captureIntentService;
        this.completeRefundService = completeRefundService;
        this.failIntentService = failIntentService;
        this.failRefundService = failRefundService;
        this.clock = clock;
    }

    @Override
    public ProcessWebhookEventsUsecaseResponse execute(ProcessWebhookEventsUsecaseRequest request) {
        int processed = 0;
        for (var event : events.findUnprocessed(request.getLimit())) {
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
        return new ProcessWebhookEventsUsecaseResponse(processed);
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
                    captureIntentService.execute(CaptureIntentServiceRequest.builder().intent(intent).build());
                }
            }
            case PAYMENT_FAILED -> {
                var intent = findIntent(event, n);
                if (intent == null) {
                    return isStale(event);
                }
                if (intent.status().isOpen()) {
                    failIntentService.execute(FailIntentServiceRequest.builder().intent(intent).code(n.failureCode()).message(n.failureMessage()).build());
                }
            }
            case PAYMENT_CANCELLED -> {
                var intent = findIntent(event, n);
                if (intent == null) {
                    return isStale(event);
                }
                if (intent.status().isOpen()) {
                    cancelIntentService.execute(CancelIntentServiceRequest.builder().intent(intent).build());
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
                        completeRefundService.execute(CompleteRefundServiceRequest.builder().refund(refund).providerRefundId(n.providerRefundId()).build());
                    } else {
                        failRefundService.execute(FailRefundServiceRequest.builder().refund(refund).build());
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
