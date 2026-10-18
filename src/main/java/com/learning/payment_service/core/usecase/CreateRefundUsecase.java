package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.service.refund.complete.CompleteRefundService;
import com.learning.payment_service.core.service.refund.complete.CompleteRefundServiceRequest;
import com.learning.payment_service.core.service.refund.fail.FailRefundService;
import com.learning.payment_service.core.service.refund.fail.FailRefundServiceRequest;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.outbox.OutboxServiceRequest;
import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.domain.RefundStatus;
import com.learning.payment_service.core.provider.PaymentProvider.RefundCommand;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * TDD §14.5: refund by order, keyed by an idempotency key such as {@code refund_<returnId>}, passed on to the PSP
 * as its own key. The refundable balance is captured amount minus refunds that are not FAILED; creating a refund
 * also bumps the intent's version, so two concurrent refunds cannot both pass the balance check.
 */
public class CreateRefundUsecase implements Usecase<CreateRefundUsecaseRequest, CreateRefundUsecaseResponse> {



    private final PaymentGateway gateway;
    private final ProviderRegistry providers;
    private final RetryingProviderCall retry;
    private final CompleteRefundService completeRefundService;
    private final FailRefundService failRefundService;
    private final OutboxService outboxService;
    private final Clock clock;

    public CreateRefundUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry, CompleteRefundService completeRefundService, FailRefundService failRefundService, OutboxService outboxService, Clock clock) {
        this.gateway = gateway;
        this.providers = providers;
        this.retry = retry;
        this.completeRefundService = completeRefundService;
        this.failRefundService = failRefundService;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Override
    public CreateRefundUsecaseResponse execute(CreateRefundUsecaseRequest request) {
        validate(request);

        Refund refund;
        boolean created;
        var existing = gateway.findRefundByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            refund = requireSame(existing.get(), request);
            created = false;
        } else {
            try {
                refund = insertNew(request);
                created = true;
            } catch (DuplicateIdempotencyKeyException lostRace) {
                refund = requireSame(gateway.findRefundByIdempotencyKey(request.getIdempotencyKey()).orElseThrow(), request);
                created = false;
            }
        }

        var intent = gateway.findIntent(refund.intentId()).orElseThrow();
        if (refund.status() == RefundStatus.INITIATED) {
            refund = submitToProvider(intent, refund);
            intent = gateway.findIntent(refund.intentId()).orElseThrow();
        }
        return new CreateRefundUsecaseResponse(refund, intent, created);
    }

    private Refund insertNew(CreateRefundUsecaseRequest request) {
        var intents = gateway.findIntentsByOrder(request.getOrderId());
        var intent = intents.stream().filter(i -> i.status().isRefundable())
                .max(Comparator.comparing(PaymentIntent::createdAt))
                .orElseThrow(() -> intents.stream().anyMatch(i -> i.status().wasCaptured())
                        ? new BusinessRuleViolationException("REFUND_EXCEEDS_CAPTURED",
                                "Order " + request.getOrderId() + " is already fully refunded")
                        : new BusinessRuleViolationException("PAYMENT_NOT_CAPTURED",
                                "Order " + request.getOrderId() + " has no captured payment to refund"));

        var remaining = intent.amount().subtract(gateway.sumReservedRefunds(intent.id()));
        var amount = request.getAmount() != null ? request.getAmount() : remaining;
        if (amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            throw new BusinessRuleViolationException("REFUND_EXCEEDS_CAPTURED",
                    "Refund of " + amount + " exceeds the refundable balance of " + remaining);
        }
        var now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var refund = Refund.initiated(intent, amount, request.getReason(), request.getIdempotencyKey(), now);
        return gateway.inTransaction(() -> {
            gateway.updateIntent(intent.withStatus(intent.status(), now)); // version bump: serialises refunds
            var saved = gateway.insertRefund(refund);
            var data = new LinkedHashMap<String, Object>();
            data.put("orderId", saved.orderId().toString());
            data.put("refundId", saved.id().toString());
            data.put("amount", saved.amount());
            data.put("reason", saved.reason());
            outboxService.execute(OutboxServiceRequest.builder().aggregateId(saved.id()).eventType("REFUND_INITIATED").data(data).build());
            return saved;
        });
    }

    private Refund submitToProvider(PaymentIntent intent, Refund refund) {
        var provider = providers.get(intent.provider());
        try {
            var result = retry.call(() -> provider.refund(new RefundCommand(intent.providerIntentId(),
                    refund.amount(), refund.currency(), refund.reason(), refund.idempotencyKey())));
            return switch (result.outcome()) {
                case SUCCEEDED -> completeRefundService.execute(CompleteRefundServiceRequest.builder().refund(refund).providerRefundId(result.providerRefundId()).build()).getRefund();
                case PROCESSING -> gateway.updateRefund(refund.processing(result.providerRefundId()));
                case FAILED -> {
                    failRefundService.execute(FailRefundServiceRequest.builder().refund(refund).build());
                    throw new BusinessRuleViolationException("REFUND_REJECTED", result.failureMessage());
                }
            };
        } catch (ProviderTransientException e) {
            throw new DependencyUnavailableException(
                    "Payment provider unavailable. Retry with the same Idempotency-Key.", e);
        } catch (ProviderRejectedException e) {
            failRefundService.execute(FailRefundServiceRequest.builder().refund(refund).build());
            throw new BusinessRuleViolationException("REFUND_REJECTED", e.getMessage());
        }
    }

    private static Refund requireSame(Refund existing, CreateRefundUsecaseRequest request) {
        boolean same = existing.orderId().equals(request.getOrderId())
                && (request.getAmount() == null || existing.amount().compareTo(request.getAmount()) == 0);
        if (!same) {
            throw new BusinessRuleViolationException("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different refund request");
        }
        return existing;
    }

    private static void validate(CreateRefundUsecaseRequest r) {
        if (r.getIdempotencyKey() == null || r.getIdempotencyKey().isBlank() || r.getIdempotencyKey().length() > 80) {
            throw new BusinessRuleViolationException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must be at most 80 characters");
        }
        if (r.getAmount() != null && (r.getAmount().signum() <= 0 || r.getAmount().scale() > 4)) {
            throw new BusinessRuleViolationException("INVALID_AMOUNT",
                    "amount must be positive with at most 4 decimal places");
        }
    }
}
