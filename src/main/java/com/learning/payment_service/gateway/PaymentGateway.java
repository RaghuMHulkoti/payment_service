package com.learning.payment_service.gateway;

import com.learning.payment_service.core.domain.PaymentAttempt;
import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.domain.Refund;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Persistence for intents, attempts, refunds and the outbox. Updates are optimistic (version column). */
public interface PaymentGateway {

    /** Runs {@code work} in one database transaction; used so a state change and its outbox row commit together. */
    <T> T inTransaction(Supplier<T> work);

    Optional<PaymentIntent> findIntent(UUID id);

    Optional<PaymentIntent> findIntentByIdempotencyKey(String idempotencyKey);

    Optional<PaymentIntent> findIntentByProviderIntentId(Provider provider, String providerIntentId);

    List<PaymentIntent> findIntentsByOrder(UUID orderId);

    /** Open intents last touched before {@code olderThan}, oldest first. */
    List<PaymentIntent> findStuckIntents(OffsetDateTime olderThan, int limit);

    /** @throws com.learning.payment_service.exception.DuplicateIdempotencyKeyException on a key collision */
    PaymentIntent insertIntent(PaymentIntent intent);

    /** @throws com.learning.payment_service.exception.ConflictException if the row changed since it was read */
    PaymentIntent updateIntent(PaymentIntent intent);

    int nextAttemptNo(UUID intentId);

    void insertAttempt(PaymentAttempt attempt);

    Optional<Refund> findRefund(UUID id);

    Optional<Refund> findRefundByIdempotencyKey(String idempotencyKey);

    Optional<Refund> findRefundByProviderRefundId(String providerRefundId);

    List<Refund> findRefundsByIntent(UUID intentId);

    /** Sum of refunds that still count against the refundable balance (not FAILED). */
    BigDecimal sumReservedRefunds(UUID intentId);

    /** Sum of refunds that actually moved money. */
    BigDecimal sumSucceededRefunds(UUID intentId);

    /** @throws com.learning.payment_service.exception.DuplicateIdempotencyKeyException on a key collision */
    Refund insertRefund(Refund refund);

    Refund updateRefund(Refund refund);

    void insertOutbox(UUID aggregateId, String eventType, String payload, OffsetDateTime createdAt);
}
