package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.service.intent.fail.FailIntentService;
import com.learning.payment_service.core.service.intent.fail.FailIntentServiceRequest;
import com.learning.payment_service.core.service.intent.requiresaction.MarkIntentRequiresActionService;
import com.learning.payment_service.core.service.intent.requiresaction.MarkIntentRequiresActionServiceRequest;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.outbox.OutboxServiceRequest;
import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.PaymentProvider.CreateIntentCommand;
import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;
import com.learning.payment_service.core.provider.RetryingProviderCall;
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
public class CreateIntentUsecase implements Usecase<CreateIntentUsecaseRequest, CreateIntentUsecaseResponse> {



    private final PaymentGateway gateway;
    private final ProviderRegistry providers;
    private final RetryingProviderCall retry;
    private final FailIntentService failIntentService;
    private final MarkIntentRequiresActionService markIntentRequiresActionService;
    private final OutboxService outboxService;
    private final Clock clock;

    public CreateIntentUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry, FailIntentService failIntentService, MarkIntentRequiresActionService markIntentRequiresActionService, OutboxService outboxService, Clock clock) {
        this.gateway = gateway;
        this.providers = providers;
        this.retry = retry;
        this.failIntentService = failIntentService;
        this.markIntentRequiresActionService = markIntentRequiresActionService;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Override
    public CreateIntentUsecaseResponse execute(CreateIntentUsecaseRequest request) {
        validate(request);
        var provider = resolveProvider(request);

        PaymentIntent intent;
        boolean created;
        var existing = gateway.findIntentByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            intent = requireSame(existing.get(), request, provider);
            created = false;
        } else {
            try {
                intent = insertNew(request, provider);
                created = true;
            } catch (DuplicateIdempotencyKeyException lostRace) {
                intent = requireSame(gateway.findIntentByIdempotencyKey(request.getIdempotencyKey()).orElseThrow(),
                        request, provider);
                created = false;
            }
        }

        if (intent.status() == PaymentStatus.INITIATED && intent.providerIntentId() == null) {
            intent = registerWithProvider(intent);
        }
        String clientSecret = intent.status() == PaymentStatus.REQUIRES_ACTION && intent.providerIntentId() != null
                ? providers.get(intent.provider()).clientSecret(intent.providerIntentId()) : null;
        return new CreateIntentUsecaseResponse(intent, clientSecret, created);
    }

    private PaymentIntent insertNew(CreateIntentUsecaseRequest request, Provider provider) {
        boolean alreadyPaid = gateway.findIntentsByOrder(request.getOrderId()).stream()
                .anyMatch(i -> i.status().wasCaptured());
        if (alreadyPaid) {
            throw new BusinessRuleViolationException("ORDER_ALREADY_PAID",
                    "Order " + request.getOrderId() + " already has a captured payment");
        }
        var now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var intent = PaymentIntent.initiated(request.getOrderId(), request.getCustomerId(), request.getAmount(),
                request.getCurrency(), provider, request.getIdempotencyKey(), now);
        return gateway.inTransaction(() -> {
            var saved = gateway.insertIntent(intent);
            var data = new LinkedHashMap<String, Object>();
            data.put("orderId", saved.orderId().toString());
            data.put("paymentIntentId", saved.id().toString());
            data.put("amount", saved.amount());
            data.put("currency", saved.currency());
            data.put("provider", saved.provider().name());
            outboxService.execute(OutboxServiceRequest.builder().aggregateId(saved.id()).eventType("PAYMENT_INITIATED").data(data).build());
            return saved;
        });
    }

    private PaymentIntent registerWithProvider(PaymentIntent intent) {
        var provider = providers.get(intent.provider());
        try {
            var created = retry.call(() -> provider.createIntent(new CreateIntentCommand(intent.id(),
                    intent.orderId(), intent.amount(), intent.currency(), intent.idempotencyKey())));
            return markIntentRequiresActionService.execute(MarkIntentRequiresActionServiceRequest.builder().intent(intent).providerIntentId(created.providerIntentId()).build()).getIntent();
        } catch (ProviderTransientException e) {
            throw new DependencyUnavailableException(
                    "Payment provider unavailable. Retry with the same Idempotency-Key.", e);
        } catch (ProviderRejectedException e) {
            failIntentService.execute(FailIntentServiceRequest.builder().intent(intent).code(e.getCode()).message(e.getMessage()).build());
            throw new BusinessRuleViolationException("PAYMENT_PROVIDER_REJECTED", e.getMessage());
        }
    }

    private static PaymentIntent requireSame(PaymentIntent existing, CreateIntentUsecaseRequest request, Provider provider) {
        boolean same = existing.orderId().equals(request.getOrderId())
                && existing.customerId().equals(request.getCustomerId())
                && existing.amount().compareTo(request.getAmount()) == 0
                && existing.currency().equals(request.getCurrency())
                && existing.provider() == provider;
        if (!same) {
            throw new BusinessRuleViolationException("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different payment request");
        }
        return existing;
    }

    /** INR goes through Razorpay, everything else through Stripe, unless the caller names a provider. */
    private static Provider resolveProvider(CreateIntentUsecaseRequest request) {
        if (request.getProvider() != null) {
            return request.getProvider();
        }
        return "INR".equals(request.getCurrency()) ? Provider.RAZORPAY : Provider.STRIPE;
    }

    private static void validate(CreateIntentUsecaseRequest r) {
        if (r.getIdempotencyKey() == null || r.getIdempotencyKey().isBlank() || r.getIdempotencyKey().length() > 80) {
            throw new BusinessRuleViolationException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must be at most 80 characters");
        }
        if (r.getAmount() == null || r.getAmount().signum() <= 0 || r.getAmount().scale() > 4) {
            throw new BusinessRuleViolationException("INVALID_AMOUNT",
                    "amount must be positive with at most 4 decimal places");
        }
        if (r.getCurrency() == null || !r.getCurrency().matches("[A-Z]{3}")) {
            throw new BusinessRuleViolationException("INVALID_CURRENCY", "currency must be a 3-letter ISO code");
        }
    }
}
