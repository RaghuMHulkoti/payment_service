package com.learning.payment_service.core.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentAttempt(UUID id, UUID intentId, int attemptNo, String providerChargeId, String status,
                             String rawResponse, OffsetDateTime createdAt) {
}
