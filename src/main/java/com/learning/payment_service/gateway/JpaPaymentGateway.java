package com.learning.payment_service.gateway;

import com.learning.payment_service.core.domain.PaymentAttempt;
import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.domain.RefundStatus;
import com.learning.payment_service.exception.ConflictException;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.exception.DuplicateIdempotencyKeyException;
import com.learning.payment_service.gateway.entity.PaymentAttemptEntity;
import com.learning.payment_service.gateway.entity.PaymentIntentEntity;
import com.learning.payment_service.gateway.entity.PaymentOutboxEntity;
import com.learning.payment_service.gateway.entity.RefundEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Oracle-backed payment store (schema PAYMENT) through JPA. */
public class JpaPaymentGateway implements PaymentGateway {

    private static final List<String> OPEN = List.of(PaymentStatus.INITIATED.name(), PaymentStatus.REQUIRES_ACTION.name());

    private final PaymentIntentRepository intents;
    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final PaymentOutboxRepository outbox;
    private final TransactionTemplate tx;

    public JpaPaymentGateway(PaymentIntentRepository intents, PaymentAttemptRepository attempts, RefundRepository refunds,
                             PaymentOutboxRepository outbox, TransactionTemplate tx) {
        this.intents = intents;
        this.attempts = attempts;
        this.refunds = refunds;
        this.outbox = outbox;
        this.tx = tx;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return guard("run transaction", () -> tx.execute(status -> work.get()));
    }

    // ---- intents

    @Override
    public Optional<PaymentIntent> findIntent(UUID id) {
        return guard("read payment", () -> intents.findById(id).map(JpaPaymentGateway::toDomain));
    }

    @Override
    public Optional<PaymentIntent> findIntentByIdempotencyKey(String idempotencyKey) {
        return guard("read payment", () -> intents.findByIdempotencyKey(idempotencyKey).map(JpaPaymentGateway::toDomain));
    }

    @Override
    public Optional<PaymentIntent> findIntentByProviderIntentId(Provider provider, String providerIntentId) {
        return guard("read payment", () -> intents.findByProviderAndProviderIntentId(provider.name(), providerIntentId)
                .map(JpaPaymentGateway::toDomain));
    }

    @Override
    public List<PaymentIntent> findIntentsByOrder(UUID orderId) {
        return guard("read payments", () -> intents.findByOrderIdOrderByCreatedAt(orderId).stream()
                .map(JpaPaymentGateway::toDomain).toList());
    }

    @Override
    public List<PaymentIntent> findStuckIntents(OffsetDateTime olderThan, int limit) {
        return guard("read stuck payments", () -> intents
                .findByStatusInAndUpdatedAtBeforeOrderByUpdatedAt(OPEN, olderThan, Limit.of(limit)).stream()
                .map(JpaPaymentGateway::toDomain).toList());
    }

    @Override
    public PaymentIntent insertIntent(PaymentIntent i) {
        try {
            guard("create payment", () -> intents.saveAndFlush(PaymentIntentEntity.builder().id(i.id())
                    .orderId(i.orderId()).customerId(i.customerId()).amount(i.amount()).currency(i.currency())
                    .provider(i.provider().name()).providerIntentId(i.providerIntentId()).status(i.status().name())
                    .idempotencyKey(i.idempotencyKey()).failureCode(i.failureCode()).failureMessage(i.failureMessage())
                    .version(i.version()).createdAt(i.createdAt()).updatedAt(i.updatedAt()).build()));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateIdempotencyKeyException(i.idempotencyKey());
        }
        return i;
    }

    @Override
    public PaymentIntent updateIntent(PaymentIntent i) {
        int updated = guard("update payment", () -> intents.updateIfVersion(i.id(), i.version(), i.providerIntentId(),
                i.status().name(), i.failureCode(), i.failureMessage(), i.updatedAt()));
        if (updated == 0) {
            throw new ConflictException("PAYMENT_CONFLICT", "The payment was changed by another request. Retry.");
        }
        return i.withVersion(i.version() + 1);
    }

    // ---- attempts

    @Override
    public int nextAttemptNo(UUID intentId) {
        return guard("read attempts", () -> attempts.maxAttemptNo(intentId) + 1);
    }

    @Override
    public void insertAttempt(PaymentAttempt a) {
        try {
            guard("record attempt", () -> attempts.saveAndFlush(PaymentAttemptEntity.builder().id(a.id())
                    .intentId(a.intentId()).attemptNo(a.attemptNo()).providerChargeId(a.providerChargeId())
                    .status(a.status()).rawResponse(a.rawResponse()).createdAt(a.createdAt()).build()));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("PAYMENT_CONFLICT", "Another confirmation is already in progress. Retry.");
        }
    }

    // ---- refunds

    @Override
    public Optional<Refund> findRefund(UUID id) {
        return guard("read refund", () -> refunds.findById(id).map(JpaPaymentGateway::toDomain));
    }

    @Override
    public Optional<Refund> findRefundByIdempotencyKey(String idempotencyKey) {
        return guard("read refund", () -> refunds.findByIdempotencyKey(idempotencyKey).map(JpaPaymentGateway::toDomain));
    }

    @Override
    public Optional<Refund> findRefundByProviderRefundId(String providerRefundId) {
        return guard("read refund", () -> refunds.findByProviderRefundId(providerRefundId).map(JpaPaymentGateway::toDomain));
    }

    @Override
    public List<Refund> findRefundsByIntent(UUID intentId) {
        return guard("read refunds", () -> refunds.findByIntentIdOrderByCreatedAt(intentId).stream()
                .map(JpaPaymentGateway::toDomain).toList());
    }

    @Override
    public BigDecimal sumReservedRefunds(UUID intentId) {
        return guard("read refunds", () -> orZero(refunds.sumNotFailed(intentId)));
    }

    @Override
    public BigDecimal sumSucceededRefunds(UUID intentId) {
        return guard("read refunds", () -> orZero(refunds.sumSucceeded(intentId)));
    }

    @Override
    public Refund insertRefund(Refund r) {
        try {
            guard("create refund", () -> refunds.saveAndFlush(RefundEntity.builder().id(r.id()).intentId(r.intentId())
                    .orderId(r.orderId()).amount(r.amount()).currency(r.currency()).reason(r.reason())
                    .status(r.status().name()).providerRefundId(r.providerRefundId())
                    .idempotencyKey(r.idempotencyKey()).version(r.version()).createdAt(r.createdAt())
                    .completedAt(r.completedAt()).build()));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateIdempotencyKeyException(r.idempotencyKey());
        }
        return r;
    }

    @Override
    public Refund updateRefund(Refund r) {
        int updated = guard("update refund", () -> refunds.updateIfVersion(r.id(), r.version(), r.status().name(),
                r.providerRefundId(), r.completedAt()));
        if (updated == 0) {
            throw new ConflictException("REFUND_CONFLICT", "The refund was changed by another request. Retry.");
        }
        return r.withVersion(r.version() + 1);
    }

    // ---- outbox

    @Override
    public void insertOutbox(UUID aggregateId, String eventType, String payload, OffsetDateTime createdAt) {
        guard("write outbox", () -> outbox.save(PaymentOutboxEntity.builder().aggregateId(aggregateId)
                .eventType(eventType).payload(payload).status("PENDING").createdAt(createdAt).build()));
    }

    // ---- mapping

    private static PaymentIntent toDomain(PaymentIntentEntity e) {
        return new PaymentIntent(e.getId(), e.getOrderId(), e.getCustomerId(), e.getAmount(), e.getCurrency().trim(),
                Provider.valueOf(e.getProvider()), e.getProviderIntentId(), PaymentStatus.valueOf(e.getStatus()),
                e.getIdempotencyKey(), e.getFailureCode(), e.getFailureMessage(), e.getVersion(), utc(e.getCreatedAt()),
                utc(e.getUpdatedAt()));
    }

    private static Refund toDomain(RefundEntity e) {
        return new Refund(e.getId(), e.getIntentId(), e.getOrderId(), e.getAmount(), e.getCurrency().trim(),
                e.getReason(), RefundStatus.valueOf(e.getStatus()), e.getProviderRefundId(), e.getIdempotencyKey(),
                e.getVersion(), utc(e.getCreatedAt()), utc(e.getCompletedAt()));
    }

    static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Unique-key and conflict outcomes are the caller's to interpret; only a broken store becomes a 503. */
    private <T> T guard(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataIntegrityViolationException | ConflictException | DuplicateIdempotencyKeyException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Payment store unavailable: could not " + action, e);
        }
    }
}
