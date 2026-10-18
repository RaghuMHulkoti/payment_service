package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.PaymentProvider.CreateIntentCommand;
import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;
import com.learning.payment_service.core.provider.RetryingProviderCall;
import com.learning.payment_service.core.service.OutboxWriter;
import com.learning.payment_service.core.service.PaymentStateService;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.exception.DuplicateIdempotencyKeyException;
import com.learning.payment_service.gateway.PaymentGateway;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * TDD §14.3: dedupe by idempotency key, persist the intent, create it at the PSP with the same key, return the
 * client secret. Replaying the same key returns the same intent; if the first call died before the PSP answered
 * (intent still INITIATED, no provider id) the replay finishes the job.
 */
public class CreateIntentUsecase implements Usecase<CreateIntentUsecase.Request, CreateIntentUsecase.Response> {

    public record Request(String idempotencyKey, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                          Provider provider) implements UsecaseRequest {
    }

    public record Response(PaymentIntent intent, String clientSecret, boolean created) implements UsecaseResponse {
    }

    private final PaymentGateway gateway;
    private final ProviderRegistry providers;
    private final RetryingProviderCall retry;
    private final PaymentStateService state;
    private final OutboxWriter outbox;
    private final Clock clock;

    public CreateIntentUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry,
                               PaymentStateService state, OutboxWriter outbox, Clock clock) {
        this.gateway = gateway;
        this.providers = providers;
        this.retry = retry;
        this.state = state;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        validate(request);
        var provider = resolveProvider(request);

        PaymentIntent intent;
        boolean created;
        var existing = gateway.findIntentByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            intent = requireSame(existing.get(), request, provider);
            created = false;
        } else {
            try {
                intent = insertNew(request, provider);
                created = true;
            } catch (DuplicateIdempotencyKeyException lostRace) {
                intent = requireSame(gateway.findIntentByIdempotencyKey(request.idempotencyKey()).orElseThrow(),
                        request, provider);
                created = false;
            }
        }

        if (intent.status() == PaymentStatus.INITIATED && intent.providerIntentId() == null) {
            intent = registerWithProvider(intent);
        }
        String clientSecret = intent.status() == PaymentStatus.REQUIRES_ACTION && intent.providerIntentId() != null
                ? providers.get(intent.provider()).clientSecret(intent.providerIntentId()) : null;
        return new Response(intent, clientSecret, created);
    }

    private PaymentIntent insertNew(Request request, Provider provider) {
        boolean alreadyPaid = gateway.findIntentsByOrder(request.orderId()).stream()
                .anyMatch(i -> i.status().wasCaptured());
        if (alreadyPaid) {
            throw new BusinessRuleViolationException("ORDER_ALREADY_PAID",
                    "Order " + request.orderId() + " already has a captured payment");
        }
        var now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var intent = PaymentIntent.initiated(request.orderId(), request.customerId(), request.amount(),
                request.currency(), provider, request.idempotencyKey(), now);
        return gateway.inTransaction(() -> {
            var saved = gateway.insertIntent(intent);
            var data = new LinkedHashMap<String, Object>();
            data.put("orderId", saved.orderId().toString());
            data.put("paymentIntentId", saved.id().toString());
            data.put("amount", saved.amount());
            data.put("currency", saved.currency());
            data.put("provider", saved.provider().name());
            outbox.emit(saved.id(), "PAYMENT_INITIATED", data);
            return saved;
        });
    }

    private PaymentIntent registerWithProvider(PaymentIntent intent) {
        var provider = providers.get(intent.provider());
        try {
            var created = retry.call(() -> provider.createIntent(new CreateIntentCommand(intent.id(),
                    intent.orderId(), intent.amount(), intent.currency(), intent.idempotencyKey())));
            return state.markRequiresAction(intent, created.providerIntentId());
        } catch (ProviderTransientException e) {
            throw new DependencyUnavailableException(
                    "Payment provider unavailable. Retry with the same Idempotency-Key.", e);
        } catch (ProviderRejectedException e) {
            state.markFailed(intent, e.getCode(), e.getMessage());
            throw new BusinessRuleViolationException("PAYMENT_PROVIDER_REJECTED", e.getMessage());
        }
    }

    private static PaymentIntent requireSame(PaymentIntent existing, Request request, Provider provider) {
        boolean same = existing.orderId().equals(request.orderId())
                && existing.customerId().equals(request.customerId())
                && existing.amount().compareTo(request.amount()) == 0
                && existing.currency().equals(request.currency())
                && existing.provider() == provider;
        if (!same) {
            throw new BusinessRuleViolationException("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different payment request");
        }
        return existing;
    }

    /** INR goes through Razorpay, everything else through Stripe, unless the caller names a provider. */
    private static Provider resolveProvider(Request request) {
        if (request.provider() != null) {
            return request.provider();
        }
        return "INR".equals(request.currency()) ? Provider.RAZORPAY : Provider.STRIPE;
    }

    private static void validate(Request r) {
        if (r.idempotencyKey() == null || r.idempotencyKey().isBlank() || r.idempotencyKey().length() > 80) {
            throw new BusinessRuleViolationException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must be at most 80 characters");
        }
        if (r.amount() == null || r.amount().signum() <= 0 || r.amount().scale() > 4) {
            throw new BusinessRuleViolationException("INVALID_AMOUNT",
                    "amount must be positive with at most 4 decimal places");
        }
        if (r.currency() == null || !r.currency().matches("[A-Z]{3}")) {
            throw new BusinessRuleViolationException("INVALID_CURRENCY", "currency must be a 3-letter ISO code");
        }
    }
}
