package com.learning.payment_service.gateway.webhook;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.WebhookHandler;
import com.learning.payment_service.exception.InvalidWebhookException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.learning.payment_service.core.provider.WebhookHandler.EventKind.*;
import static com.learning.payment_service.gateway.webhook.WebhookJson.text;

/**
 * Stripe: {@code Stripe-Signature: t=<unix>,v1=<hex>}, where v1 = HMAC-SHA256(secret, "<t>.<body>").
 * Deliveries older than the tolerance are rejected to stop replays.
 */
public class StripeWebhookHandler implements WebhookHandler {

    private final String secret;
    private final Duration tolerance;
    private final Clock clock;

    public StripeWebhookHandler(String secret, Duration tolerance, Clock clock) {
        this.secret = secret;
        this.tolerance = tolerance;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.STRIPE;
    }

    @Override
    public boolean verify(WebhookRequest request) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        String header = request.header().apply("Stripe-Signature");
        if (header == null) {
            return false;
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                timestamp = kv[1];
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            return false;
        }
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(signedAt), clock.instant()).abs().compareTo(tolerance) > 0) {
            return false;
        }
        String expected = Hmac.sha256Hex(secret, timestamp + "." + request.body());
        return signatures.stream().anyMatch(s -> Hmac.matches(expected, s));
    }

    @Override
    public String eventId(WebhookRequest request) {
        String id = text(WebhookJson.parse(request.body()).get("id"));
        if (id == null || id.isBlank()) {
            throw InvalidWebhookException.malformed("Stripe event has no id");
        }
        return id;
    }

    @Override
    public String eventType(String body) {
        String type = text(WebhookJson.parse(body).get("type"));
        return type == null ? "unknown" : type;
    }

    @Override
    public NormalizedWebhook normalize(String body) {
        JsonNode root = WebhookJson.parse(body);
        JsonNode object = root.path("data").path("object");
        return switch (eventType(body)) {
            case "payment_intent.succeeded" -> new NormalizedWebhook(PAYMENT_SUCCEEDED, text(object.get("id")),
                    null, null, null);
            case "payment_intent.payment_failed" -> {
                JsonNode error = object.path("last_payment_error");
                String code = text(error.get("code"));
                yield new NormalizedWebhook(PAYMENT_FAILED, text(object.get("id")), null,
                        code != null ? code : "payment_failed", text(error.get("message")));
            }
            case "payment_intent.canceled" -> new NormalizedWebhook(PAYMENT_CANCELLED, text(object.get("id")),
                    null, null, null);
            case "refund.updated", "charge.refund.updated" -> {
                String status = text(object.get("status"));
                var kind = "succeeded".equals(status) ? REFUND_SUCCEEDED : "failed".equals(status) ? REFUND_FAILED : IGNORED;
                yield new NormalizedWebhook(kind, text(object.get("payment_intent")), text(object.get("id")),
                        text(object.get("failure_reason")), null);
            }
            default -> NormalizedWebhook.ignored();
        };
    }
}
