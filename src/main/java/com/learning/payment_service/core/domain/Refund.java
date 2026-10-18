package com.learning.payment_service.core.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Refund(UUID id, UUID intentId, UUID orderId, BigDecimal amount, String currency, String reason,
                     RefundStatus status, String providerRefundId, String idempotencyKey, long version,
                     OffsetDateTime createdAt, OffsetDateTime completedAt) {

    public static Refund initiated(PaymentIntent intent, BigDecimal amount, String reason, String idempotencyKey,
                                   OffsetDateTime now) {
        return new Refund(UUID.randomUUID(), intent.id(), intent.orderId(), amount, intent.currency(), reason,
                RefundStatus.INITIATED, null, idempotencyKey, 0, now, null);
    }

    public Refund processing(String providerRefundId) {
        return new Refund(id, intentId, orderId, amount, currency, reason, RefundStatus.PROCESSING,
                providerRefundId, idempotencyKey, version, createdAt, null);
    }

    public Refund succeeded(String providerRefundId, OffsetDateTime now) {
        return new Refund(id, intentId, orderId, amount, currency, reason, RefundStatus.SUCCESS,
                providerRefundId != null ? providerRefundId : this.providerRefundId, idempotencyKey, version,
                createdAt, now);
    }

    public Refund failed(OffsetDateTime now) {
        return new Refund(id, intentId, orderId, amount, currency, reason, RefundStatus.FAILED, providerRefundId,
                idempotencyKey, version, createdAt, now);
    }

    public Refund withVersion(long version) {
        return new Refund(id, intentId, orderId, amount, currency, reason, status, providerRefundId,
                idempotencyKey, version, createdAt, completedAt);
    }
}
