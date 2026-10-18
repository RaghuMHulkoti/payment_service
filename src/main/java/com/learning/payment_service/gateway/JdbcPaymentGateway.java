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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.learning.payment_service.gateway.JdbcSupport.raw;
import static com.learning.payment_service.gateway.JdbcSupport.time;
import static com.learning.payment_service.gateway.JdbcSupport.uuid;

/** Oracle-backed payment store (schema PAYMENT). */
public class JdbcPaymentGateway implements PaymentGateway {

    private static final String INTENT_COLUMNS = """
            id, order_id, customer_id, amount, currency, provider, provider_intent_id, status, idempotency_key,
            failure_code, failure_message, version, created_at, updated_at""";

    private static final String REFUND_COLUMNS = """
            id, intent_id, order_id, amount, currency, reason, status, provider_refund_id, idempotency_key,
            version, created_at, completed_at""";

    private static final RowMapper<PaymentIntent> INTENT = (rs, n) -> new PaymentIntent(
            uuid(rs, "id"), uuid(rs, "order_id"), uuid(rs, "customer_id"), rs.getBigDecimal("amount"),
            rs.getString("currency").trim(), Provider.valueOf(rs.getString("provider")),
            rs.getString("provider_intent_id"), PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("idempotency_key"), rs.getString("failure_code"), rs.getString("failure_message"),
            rs.getLong("version"), time(rs, "created_at"), time(rs, "updated_at"));

    private static final RowMapper<Refund> REFUND = (rs, n) -> new Refund(
            uuid(rs, "id"), uuid(rs, "intent_id"), uuid(rs, "order_id"), rs.getBigDecimal("amount"),
            rs.getString("currency").trim(), rs.getString("reason"), RefundStatus.valueOf(rs.getString("status")),
            rs.getString("provider_refund_id"), rs.getString("idempotency_key"), rs.getLong("version"),
            time(rs, "created_at"), time(rs, "completed_at"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcPaymentGateway(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return guard("run transaction", () -> tx.execute(status -> work.get()));
    }

    // ---- intents

    @Override
    public Optional<PaymentIntent> findIntent(UUID id) {
        return guard("read payment", () -> first(jdbc.query(
                "SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE id = ?", INTENT, raw(id))));
    }

    @Override
    public Optional<PaymentIntent> findIntentByIdempotencyKey(String idempotencyKey) {
        return guard("read payment", () -> first(jdbc.query(
                "SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE idempotency_key = ?", INTENT,
                idempotencyKey)));
    }

    @Override
    public Optional<PaymentIntent> findIntentByProviderIntentId(Provider provider, String providerIntentId) {
        return guard("read payment", () -> first(jdbc.query(
                "SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE provider = ? AND provider_intent_id = ?",
                INTENT, provider.name(), providerIntentId)));
    }

    @Override
    public List<PaymentIntent> findIntentsByOrder(UUID orderId) {
        return guard("read payments", () -> jdbc.query(
                "SELECT " + INTENT_COLUMNS + " FROM payment_intents WHERE order_id = ? ORDER BY created_at",
                INTENT, raw(orderId)));
    }

    @Override
    public List<PaymentIntent> findStuckIntents(OffsetDateTime olderThan, int limit) {
        return guard("read stuck payments", () -> jdbc.query(
                "SELECT " + INTENT_COLUMNS + " FROM payment_intents "
                        + "WHERE status IN ('INITIATED','REQUIRES_ACTION') AND updated_at < ? "
                        + "ORDER BY updated_at FETCH FIRST ? ROWS ONLY", INTENT, olderThan, limit));
    }

    @Override
    public PaymentIntent insertIntent(PaymentIntent i) {
        try {
            guard("create payment", () -> jdbc.update(
                    "INSERT INTO payment_intents (" + INTENT_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    raw(i.id()), raw(i.orderId()), raw(i.customerId()), i.amount(), i.currency(),
                    i.provider().name(), i.providerIntentId(), i.status().name(), i.idempotencyKey(),
                    i.failureCode(), i.failureMessage(), i.version(), i.createdAt(), i.updatedAt()));
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException(i.idempotencyKey());
        }
        return i;
    }

    @Override
    public PaymentIntent updateIntent(PaymentIntent i) {
        int updated = guard("update payment", () -> jdbc.update("""
                UPDATE payment_intents
                SET provider_intent_id = ?, status = ?, failure_code = ?, failure_message = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND version = ?""",
                i.providerIntentId(), i.status().name(), i.failureCode(), i.failureMessage(), i.updatedAt(),
                raw(i.id()), i.version()));
        if (updated == 0) {
            throw new ConflictException("PAYMENT_CONFLICT", "The payment was changed by another request. Retry.");
        }
        return i.withVersion(i.version() + 1);
    }

    // ---- attempts

    @Override
    public int nextAttemptNo(UUID intentId) {
        return guard("read attempts", () -> {
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(attempt_no), 0) FROM payment_attempts WHERE intent_id = ?",
                    Integer.class, raw(intentId));
            return (max == null ? 0 : max) + 1;
        });
    }

    @Override
    public void insertAttempt(PaymentAttempt a) {
        try {
            guard("record attempt", () -> jdbc.update("""
                    INSERT INTO payment_attempts (id, intent_id, attempt_no, provider_charge_id, status, raw_response, created_at)
                    VALUES (?,?,?,?,?,?,?)""",
                    raw(a.id()), raw(a.intentId()), a.attemptNo(), a.providerChargeId(), a.status(), a.rawResponse(),
                    a.createdAt()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("PAYMENT_CONFLICT", "Another confirmation is already in progress. Retry.");
        }
    }

    // ---- refunds

    @Override
    public Optional<Refund> findRefund(UUID id) {
        return guard("read refund", () -> first(jdbc.query(
                "SELECT " + REFUND_COLUMNS + " FROM refunds WHERE id = ?", REFUND, raw(id))));
    }

    @Override
    public Optional<Refund> findRefundByIdempotencyKey(String idempotencyKey) {
        return guard("read refund", () -> first(jdbc.query(
                "SELECT " + REFUND_COLUMNS + " FROM refunds WHERE idempotency_key = ?", REFUND, idempotencyKey)));
    }

    @Override
    public Optional<Refund> findRefundByProviderRefundId(String providerRefundId) {
        return guard("read refund", () -> first(jdbc.query(
                "SELECT " + REFUND_COLUMNS + " FROM refunds WHERE provider_refund_id = ?", REFUND,
                providerRefundId)));
    }

    @Override
    public List<Refund> findRefundsByIntent(UUID intentId) {
        return guard("read refunds", () -> jdbc.query(
                "SELECT " + REFUND_COLUMNS + " FROM refunds WHERE intent_id = ? ORDER BY created_at", REFUND,
                raw(intentId)));
    }

    @Override
    public BigDecimal sumReservedRefunds(UUID intentId) {
        return sum(intentId, "status <> 'FAILED'");
    }

    @Override
    public BigDecimal sumSucceededRefunds(UUID intentId) {
        return sum(intentId, "status = 'SUCCESS'");
    }

    private BigDecimal sum(UUID intentId, String condition) {
        return guard("read refunds", () -> {
            BigDecimal total = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE intent_id = ? AND " + condition,
                    BigDecimal.class, raw(intentId));
            return total == null ? BigDecimal.ZERO : total;
        });
    }

    @Override
    public Refund insertRefund(Refund r) {
        try {
            guard("create refund", () -> jdbc.update(
                    "INSERT INTO refunds (" + REFUND_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    raw(r.id()), raw(r.intentId()), raw(r.orderId()), r.amount(), r.currency(), r.reason(),
                    r.status().name(), r.providerRefundId(), r.idempotencyKey(), r.version(), r.createdAt(),
                    r.completedAt()));
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException(r.idempotencyKey());
        }
        return r;
    }

    @Override
    public Refund updateRefund(Refund r) {
        int updated = guard("update refund", () -> jdbc.update("""
                UPDATE refunds
                SET status = ?, provider_refund_id = ?, completed_at = ?, version = version + 1
                WHERE id = ? AND version = ?""",
                r.status().name(), r.providerRefundId(), r.completedAt(), raw(r.id()), r.version()));
        if (updated == 0) {
            throw new ConflictException("REFUND_CONFLICT", "The refund was changed by another request. Retry.");
        }
        return r.withVersion(r.version() + 1);
    }

    // ---- outbox

    @Override
    public void insertOutbox(UUID aggregateId, String eventType, String payload, OffsetDateTime createdAt) {
        guard("write outbox", () -> jdbc.update(
                "INSERT INTO payment_outbox (aggregate_id, event_type, payload, status, created_at) VALUES (?,?,?,'PENDING',?)",
                raw(aggregateId), eventType, payload, createdAt));
    }

    // ---- helpers

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    private <T> T guard(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (DuplicateKeyException | ConflictException | DuplicateIdempotencyKeyException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Payment store unavailable: could not " + action, e);
        }
    }
}
