package com.learning.payment_service.gateway.webhook;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.WebhookHandler;
import com.learning.payment_service.exception.InvalidWebhookException;
import tools.jackson.databind.JsonNode;

import static com.learning.payment_service.core.provider.WebhookHandler.EventKind.*;
import static com.learning.payment_service.gateway.webhook.WebhookJson.text;

/**
 * Razorpay: {@code X-Razorpay-Signature} = hex HMAC-SHA256(secret, rawBody); the delivery id is in
 * {@code X-Razorpay-Event-Id}. Our provider intent id for Razorpay is the Razorpay {@code order_id}.
 */
public class RazorpayWebhookHandler implements WebhookHandler {

    private final String secret;

    public RazorpayWebhookHandler(String secret) {
        this.secret = secret;
    }

    @Override
    public Provider provider() {
        return Provider.RAZORPAY;
    }

    @Override
    public boolean verify(WebhookRequest request) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        String signature = request.header().apply("X-Razorpay-Signature");
        return signature != null && Hmac.matches(Hmac.sha256Hex(secret, request.body()), signature);
    }

    @Override
    public String eventId(WebhookRequest request) {
        String id = request.header().apply("X-Razorpay-Event-Id");
        if (id == null || id.isBlank()) {
            throw InvalidWebhookException.malformed("Razorpay delivery has no X-Razorpay-Event-Id header");
        }
        return id;
    }

    @Override
    public String eventType(String body) {
        String event = text(WebhookJson.parse(body).get("event"));
        return event == null ? "unknown" : event;
    }

    @Override
    public NormalizedWebhook normalize(String body) {
        JsonNode payload = WebhookJson.parse(body).path("payload");
        JsonNode payment = payload.path("payment").path("entity");
        JsonNode refund = payload.path("refund").path("entity");
        return switch (eventType(body)) {
            case "payment.captured" -> new NormalizedWebhook(PAYMENT_SUCCEEDED, text(payment.get("order_id")),
                    null, null, null);
            case "payment.failed" -> new NormalizedWebhook(PAYMENT_FAILED, text(payment.get("order_id")), null,
                    text(payment.get("error_code")) != null ? text(payment.get("error_code")) : "payment_failed",
                    text(payment.get("error_description")));
            case "refund.processed" -> new NormalizedWebhook(REFUND_SUCCEEDED, null, text(refund.get("id")),
                    null, null);
            case "refund.failed" -> new NormalizedWebhook(REFUND_FAILED, null, text(refund.get("id")), null, null);
            default -> NormalizedWebhook.ignored();
        };
    }
}
