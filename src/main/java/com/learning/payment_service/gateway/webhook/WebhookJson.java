package com.learning.payment_service.gateway.webhook;

import com.learning.payment_service.exception.InvalidWebhookException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class WebhookJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private WebhookJson() {
    }

    static JsonNode parse(String body) {
        try {
            var root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                throw InvalidWebhookException.malformed("Webhook body is not a JSON object");
            }
            return root;
        } catch (tools.jackson.core.JacksonException e) {
            throw InvalidWebhookException.malformed("Webhook body is not valid JSON");
        }
    }

    /** The text at {@code node}, or null when it is missing or not text. */
    static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }
}
