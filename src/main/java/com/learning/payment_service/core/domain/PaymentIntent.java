package com.learning.payment_service.core.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Our normalised payment contract; {@code providerIntentId} links to Stripe {@code pi_xxx} / Razorpay {@code order_xxx}. */
public record PaymentIntent(UUID id, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                            Provider provider, String providerIntentId, PaymentStatus status,
                            String idempotencyKey, String failureCode, String failureMessage, long version,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static PaymentIntent initiated(UUID orderId, UUID customerId, BigDecimal amount, String currency,
                                          Provider provider, String idempotencyKey, OffsetDateTime now) {
        return new PaymentIntent(UUID.randomUUID(), orderId, customerId, amount, currency, provider, null,
                PaymentStatus.INITIATED, idempotencyKey, null, null, 0, now, now);
    }

    public PaymentIntent withProviderIntent(String providerIntentId, PaymentStatus status, OffsetDateTime now) {
        return new PaymentIntent(id, orderId, customerId, amount, currency, provider, providerIntentId, status,
                idempotencyKey, failureCode, failureMessage, version, createdAt, now);
    }

    public PaymentIntent withStatus(PaymentStatus status, OffsetDateTime now) {
        return new PaymentIntent(id, orderId, customerId, amount, currency, provider, providerIntentId, status,
                idempotencyKey, null, null, version, createdAt, now);
    }

    public PaymentIntent failed(String code, String message, OffsetDateTime now) {
        return new PaymentIntent(id, orderId, customerId, amount, currency, provider, providerIntentId,
                PaymentStatus.FAILED, idempotencyKey, code, truncate(message, 500), version, createdAt, now);
    }

    public PaymentIntent withVersion(long version) {
        return new PaymentIntent(id, orderId, customerId, amount, currency, provider, providerIntentId, status,
                idempotencyKey, failureCode, failureMessage, version, createdAt, updatedAt);
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
