package com.learning.payment_service.core.service;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only place that moves an intent or refund between states. Each transition and its outbox event commit in
 * one transaction, so an event is never lost and never published for a change that did not happen. Used by the
 * confirm API, the webhook processor and reconciliation alike.
 */
public class PaymentStateService {

    private final PaymentGateway gateway;
    private final OutboxWriter outbox;
    private final Clock clock;

    public PaymentStateService(PaymentGateway gateway, OutboxWriter outbox, Clock clock) {
        this.gateway = gateway;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Provider accepted the intent; the customer must now confirm with the client secret. */
    public PaymentIntent markRequiresAction(PaymentIntent intent, String providerIntentId) {
        return gateway.updateIntent(intent.withProviderIntent(providerIntentId, PaymentStatus.REQUIRES_ACTION, now()));
    }

    public PaymentIntent markCaptured(PaymentIntent intent) {
        return gateway.inTransaction(() -> {
            var now = now();
            var updated = gateway.updateIntent(intent.withStatus(PaymentStatus.CAPTURED, now));
            var data = base(updated);
            data.put("capturedAt", now.toString());
            outbox.emit(updated.id(), "PAYMENT_SUCCESS", data);
            return updated;
        });
    }

    public PaymentIntent markFailed(PaymentIntent intent, String code, String message) {
        return gateway.inTransaction(() -> {
            var updated = gateway.updateIntent(intent.failed(code, message, now()));
            var data = base(updated);
            data.put("failureCode", updated.failureCode());
            data.put("failureMessage", updated.failureMessage());
            outbox.emit(updated.id(), "PAYMENT_FAILED", data);
            return updated;
        });
    }

    public PaymentIntent markCancelled(PaymentIntent intent) {
        return gateway.inTransaction(() -> {
            var updated = gateway.updateIntent(intent.withStatus(PaymentStatus.CANCELLED, now()));
            var data = base(updated);
            data.put("failureCode", "CANCELLED");
            outbox.emit(updated.id(), "PAYMENT_FAILED", data);
            return updated;
        });
    }

    /** Money moved back: mark the refund SUCCESS and the intent REFUNDED or PARTIALLY_REFUNDED. */
    public Refund completeRefund(Refund refund, String providerRefundId) {
        return gateway.inTransaction(() -> {
            var now = now();
            var done = gateway.updateRefund(refund.succeeded(providerRefundId, now));
            var intent = gateway.findIntent(done.intentId()).orElseThrow();
            if (intent.status().isRefundable()) {
                var refunded = gateway.sumSucceededRefunds(intent.id());
                var status = refunded.compareTo(intent.amount()) >= 0
                        ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
                gateway.updateIntent(intent.withStatus(status, now));
            }
            var data = new LinkedHashMap<String, Object>();
            data.put("orderId", done.orderId().toString());
            data.put("refundId", done.id().toString());
            data.put("amount", done.amount());
            data.put("completedAt", now.toString());
            outbox.emit(done.id(), "REFUND_COMPLETED", data);
            return done;
        });
    }

    public Refund failRefund(Refund refund) {
        return gateway.updateRefund(refund.failed(now()));
    }

    private Map<String, Object> base(PaymentIntent intent) {
        var data = new LinkedHashMap<String, Object>();
        data.put("orderId", intent.orderId().toString());
        data.put("paymentIntentId", intent.id().toString());
        data.put("amount", intent.amount());
        data.put("currency", intent.currency());
        data.put("provider", intent.provider().name());
        return data;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }
}
