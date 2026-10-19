package com.learning.payment_service.gateway.provider;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Stripe through the official SDK. With a test key ({@code sk_test_...}) it runs in Stripe's free test mode, where
 * the payment method id picks the outcome: {@code pm_card_visa} succeeds, {@code pm_card_chargeDeclined} declines,
 * {@code pm_card_threeDSecure2Required} asks for customer action. Every call carries an idempotency key.
 * Amounts are sent in minor units, which is right for two-decimal currencies such as INR and USD.
 */
public class StripePaymentProvider implements PaymentProvider {

    private final StripeClient client;

    public StripePaymentProvider(StripeClient client) {
        this.client = client;
    }

    @Override
    public Provider id() {
        return Provider.STRIPE;
    }

    @Override
    public ProviderIntent createIntent(CreateIntentCommand command) {
        var params = PaymentIntentCreateParams.builder()
                .setAmount(minorUnits(command.amount()))
                .setCurrency(command.currency().toLowerCase())
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .putMetadata("orderId", command.orderId().toString())
                .putMetadata("intentId", command.intentId().toString())
                .build();
        var created = call(() -> client.paymentIntents().create(params, options(command.idempotencyKey())));
        return new ProviderIntent(created.getId(), created.getClientSecret());
    }

    @Override
    public String clientSecret(String providerIntentId) {
        return call(() -> client.paymentIntents().retrieve(providerIntentId)).getClientSecret();
    }

    @Override
    public ChargeResult confirm(ConfirmCommand command) {
        if (command.paymentMethodToken() == null || command.paymentMethodToken().isBlank()) {
            throw new ProviderRejectedException("payment_method_missing", "A payment method id is required");
        }
        var params = PaymentIntentConfirmParams.builder().setPaymentMethod(command.paymentMethodToken()).build();
        PaymentIntent confirmed;
        try {
            confirmed = client.paymentIntents().confirm(command.providerIntentId(), params, options(command.idempotencyKey()));
        } catch (CardException e) {
            return new ChargeResult(ChargeOutcome.DECLINED, null, declineCode(e), e.getMessage(), e.getStripeError() == null
                    ? null : e.getStripeError().toJson());
        } catch (StripeException e) {
            throw translate(e);
        }
        return switch (confirmed.getStatus()) {
            case "succeeded" -> new ChargeResult(ChargeOutcome.SUCCEEDED, confirmed.getLatestCharge(), null, null,
                    "{\"status\":\"succeeded\"}");
            case "requires_action" -> new ChargeResult(ChargeOutcome.REQUIRES_ACTION, null, null, null,
                    "{\"status\":\"requires_action\"}");
            case "requires_payment_method" -> new ChargeResult(ChargeOutcome.DECLINED, null, "card_declined",
                    confirmed.getLastPaymentError() == null ? "The payment method was declined."
                            : confirmed.getLastPaymentError().getMessage(), "{\"status\":\"requires_payment_method\"}");
            default -> throw new ProviderTransientException("Stripe returned status " + confirmed.getStatus());
        };
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        var params = RefundCreateParams.builder()
                .setPaymentIntent(command.providerIntentId())
                .setAmount(minorUnits(command.amount()))
                .putMetadata("reason", String.valueOf(command.reason()))
                .build();
        Refund refund = call(() -> client.refunds().create(params, options(command.idempotencyKey())));
        return switch (String.valueOf(refund.getStatus())) {
            case "succeeded" -> new RefundResult(RefundOutcome.SUCCEEDED, refund.getId(), null);
            case "pending", "requires_action" -> new RefundResult(RefundOutcome.PROCESSING, refund.getId(), null);
            default -> new RefundResult(RefundOutcome.FAILED, refund.getId(), refund.getFailureReason());
        };
    }

    @Override
    public void cancel(String providerIntentId, String idempotencyKey) {
        try {
            client.paymentIntents().cancel(providerIntentId, PaymentIntentCancelParams.builder().build(), options(idempotencyKey));
        } catch (InvalidRequestException e) {
            // already canceled is fine; "succeeded" means it cannot be cancelled and must not look cancelled
            var current = call(() -> client.paymentIntents().retrieve(providerIntentId)).getStatus();
            if (!"canceled".equals(current)) {
                throw new ProviderRejectedException("payment_intent_not_cancelable", "Stripe intent is " + current);
            }
        } catch (StripeException e) {
            throw translate(e);
        }
    }

    @Override
    public Optional<ProviderIntentStatus> fetchStatus(String providerIntentId) {
        try {
            var intent = client.paymentIntents().retrieve(providerIntentId);
            return Optional.of(switch (intent.getStatus()) {
                case "succeeded" -> ProviderIntentStatus.SUCCEEDED;
                case "canceled" -> ProviderIntentStatus.CANCELLED;
                case "requires_payment_method" -> intent.getLastPaymentError() != null
                        ? ProviderIntentStatus.FAILED : ProviderIntentStatus.PENDING;
                default -> ProviderIntentStatus.PENDING;
            });
        } catch (InvalidRequestException e) {
            return Optional.empty(); // Stripe does not know the id
        } catch (StripeException e) {
            throw translate(e);
        }
    }

    private static long minorUnits(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static RequestOptions options(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static String declineCode(CardException e) {
        return e.getDeclineCode() != null ? e.getDeclineCode() : e.getCode() != null ? e.getCode() : "card_declined";
    }

    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    private static <T> T call(StripeCall<T> call) {
        try {
            return call.run();
        } catch (StripeException e) {
            throw translate(e);
        }
    }

    /** Transient failures are retried by the caller; anything else is a permanent refusal. */
    private static RuntimeException translate(StripeException e) {
        if (e instanceof ApiConnectionException || e instanceof RateLimitException || e instanceof ApiException) {
            return new ProviderTransientException("Stripe unavailable: " + e.getMessage(), e);
        }
        if (e instanceof AuthenticationException) {
            return new ProviderRejectedException("stripe_authentication", "Stripe rejected the API key");
        }
        return new ProviderRejectedException(e.getCode() != null ? e.getCode() : "stripe_error", e.getMessage());
    }
}
