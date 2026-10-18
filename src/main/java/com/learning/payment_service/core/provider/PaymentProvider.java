package com.learning.payment_service.core.provider;

import com.learning.payment_service.core.domain.Provider;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment-service side of a PSP (Stripe, Razorpay). Real adapters implement this; every call carries an
 * idempotency key so a retry never double-charges (TDD §14.3, §14.5). Implementations signal a retryable
 * failure (5xx, network) with {@link ProviderTransientException} and a permanent one with
 * {@link ProviderRejectedException}.
 */
public interface PaymentProvider {

    Provider id();

    ProviderIntent createIntent(CreateIntentCommand command);

    /** The secret the client uses to confirm with the PSP directly (3DS included). */
    String clientSecret(String providerIntentId);

    ChargeResult confirm(ConfirmCommand command);

    RefundResult refund(RefundCommand command);

    /** The PSP's canonical view of an intent, used by reconciliation. Empty when the PSP does not know it. */
    Optional<ProviderIntentStatus> fetchStatus(String providerIntentId);

    record CreateIntentCommand(UUID intentId, UUID orderId, BigDecimal amount, String currency,
                               String idempotencyKey) {
    }

    record ProviderIntent(String providerIntentId, String clientSecret) {
    }

    record ConfirmCommand(String providerIntentId, String paymentMethodToken, String idempotencyKey) {
    }

    enum ChargeOutcome { SUCCEEDED, REQUIRES_ACTION, DECLINED }

    record ChargeResult(ChargeOutcome outcome, String providerChargeId, String failureCode, String failureMessage,
                        String rawResponse) {
    }

    record RefundCommand(String providerIntentId, BigDecimal amount, String currency, String reason,
                         String idempotencyKey) {
    }

    enum RefundOutcome { SUCCEEDED, PROCESSING, FAILED }

    record RefundResult(RefundOutcome outcome, String providerRefundId, String failureMessage) {
    }

    enum ProviderIntentStatus { PENDING, SUCCEEDED, FAILED, CANCELLED }
}
