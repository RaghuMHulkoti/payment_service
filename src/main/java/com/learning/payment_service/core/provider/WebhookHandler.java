package com.learning.payment_service.core.provider;

import com.learning.payment_service.core.domain.Provider;

import java.util.function.UnaryOperator;

/**
 * Inbound side of a PSP: verifies the signature and turns the provider's event shape into ours (TDD §14.4).
 * The webhook endpoint only verifies and stores; applying the event happens later.
 */
public interface WebhookHandler {

    Provider provider();

    /** True only when the request was signed with this provider's secret. Fails closed when no secret is set. */
    boolean verify(WebhookRequest request);

    /** The PSP's id for this delivery, used to drop retries. */
    String eventId(WebhookRequest request);

    String eventType(String body);

    NormalizedWebhook normalize(String body);

    /** The raw body plus a case-insensitive header lookup (returns null when absent). */
    record WebhookRequest(String body, UnaryOperator<String> header) {
    }

    enum EventKind { PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_CANCELLED, REFUND_SUCCEEDED, REFUND_FAILED, IGNORED }

    record NormalizedWebhook(EventKind kind, String providerIntentId, String providerRefundId, String failureCode,
                             String failureMessage) {

        public static NormalizedWebhook ignored() {
            return new NormalizedWebhook(EventKind.IGNORED, null, null, null, null);
        }
    }
}
