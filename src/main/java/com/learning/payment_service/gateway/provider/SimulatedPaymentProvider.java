package com.learning.payment_service.gateway.provider;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in PSP so the service runs end to end without live Stripe/Razorpay keys. It honours idempotency keys
 * like a real PSP and decides the outcome from the payment-method token:
 * <ul>
 *   <li>{@code tok_fail*}: card declined</li>
 *   <li>{@code tok_3ds*}: needs customer action</li>
 *   <li>{@code tok_transient*}: simulated 5xx (retried, then 503)</li>
 *   <li>anything else: succeeds</li>
 * </ul>
 * State is in memory, so it resets on restart. Real adapters implement {@link PaymentProvider} the same way.
 */
public class SimulatedPaymentProvider implements PaymentProvider {

    private final Provider id;
    private final ConcurrentHashMap<String, String> intentByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProviderIntentStatus> statusByIntent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> refundByKey = new ConcurrentHashMap<>();

    public SimulatedPaymentProvider(Provider id) {
        this.id = id;
    }

    @Override
    public Provider id() {
        return id;
    }

    @Override
    public ProviderIntent createIntent(CreateIntentCommand command) {
        String providerIntentId = intentByKey.computeIfAbsent(command.idempotencyKey(), key -> {
            String created = (id == Provider.STRIPE ? "pi_sim_" : "order_sim_") + compact();
            statusByIntent.put(created, ProviderIntentStatus.PENDING);
            return created;
        });
        return new ProviderIntent(providerIntentId, clientSecret(providerIntentId));
    }

    @Override
    public String clientSecret(String providerIntentId) {
        return providerIntentId + "_secret_sim";
    }

    @Override
    public ChargeResult confirm(ConfirmCommand command) {
        String token = command.paymentMethodToken();
        if (token == null || token.isBlank()) {
            throw new ProviderRejectedException("payment_method_missing", "A payment method token is required");
        }
        if (token.startsWith("tok_transient")) {
            throw new ProviderTransientException("Simulated provider outage");
        }
        if (token.startsWith("tok_3ds")) {
            return new ChargeResult(ChargeOutcome.REQUIRES_ACTION, null, null, null, "{\"status\":\"requires_action\"}");
        }
        if (token.startsWith("tok_fail")) {
            statusByIntent.put(command.providerIntentId(), ProviderIntentStatus.FAILED);
            return new ChargeResult(ChargeOutcome.DECLINED, null, "card_declined", "Your card was declined.",
                    "{\"status\":\"failed\",\"code\":\"card_declined\"}");
        }
        statusByIntent.put(command.providerIntentId(), ProviderIntentStatus.SUCCEEDED);
        String chargeId = "ch_sim_" + compact();
        return new ChargeResult(ChargeOutcome.SUCCEEDED, chargeId, null, null,
                "{\"status\":\"succeeded\",\"charge\":\"" + chargeId + "\"}");
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        String providerRefundId = refundByKey.computeIfAbsent(command.idempotencyKey(), key -> "re_sim_" + compact());
        return new RefundResult(RefundOutcome.SUCCEEDED, providerRefundId, null);
    }

    @Override
    public void cancel(String providerIntentId, String idempotencyKey) {
        statusByIntent.computeIfPresent(providerIntentId,
                (id, status) -> status == ProviderIntentStatus.SUCCEEDED ? status : ProviderIntentStatus.CANCELLED);
    }

    @Override
    public Optional<ProviderIntentStatus> fetchStatus(String providerIntentId) {
        return Optional.ofNullable(statusByIntent.get(providerIntentId));
    }

    private static String compact() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
