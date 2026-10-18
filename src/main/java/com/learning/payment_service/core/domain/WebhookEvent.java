package com.learning.payment_service.core.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WebhookEvent(UUID id, Provider provider, String providerEventId, String eventType, String payload,
                           boolean signatureValid, OffsetDateTime processedAt, OffsetDateTime receivedAt) {
}
