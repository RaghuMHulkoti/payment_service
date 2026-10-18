package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentAttempt;
import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.provider.PaymentProvider.ChargeOutcome;
import com.learning.payment_service.core.provider.PaymentProvider.ChargeResult;
import com.learning.payment_service.core.provider.PaymentProvider.ConfirmCommand;
import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;
import com.learning.payment_service.core.provider.RetryingProviderCall;
import com.learning.payment_service.core.service.PaymentStateService;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.exception.PaymentNotFoundException;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Server-side confirm (optional path, TDD §11.7). Every call is one numbered attempt. A decline is an expected
 * outcome, so it returns the FAILED intent (and emits PAYMENT_FAILED for the saga); only an unreachable provider
 * is an error, and it leaves the intent untouched so the caller can retry.
 */
public class ConfirmIntentUsecase implements Usecase<ConfirmIntentUsecase.Request, ConfirmIntentUsecase.Response> {

    public record Request(UUID intentId, String paymentMethodToken) implements UsecaseRequest {
    }

    public record Response(PaymentIntent intent) implements UsecaseResponse {
    }

    private final PaymentGateway gateway;
    private final ProviderRegistry providers;
    private final RetryingProviderCall retry;
    private final PaymentStateService state;
    private final Clock clock;

    public ConfirmIntentUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry,
                                PaymentStateService state, Clock clock) {
        this.gateway = gateway;
        this.providers = providers;
        this.retry = retry;
        this.state = state;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        var intent = gateway.findIntent(request.intentId())
                .orElseThrow(() -> new PaymentNotFoundException(request.intentId()));
        if (intent.status().wasCaptured()) {
            return new Response(intent); // already paid: confirming again is a no-op
        }
        if (intent.status() != PaymentStatus.REQUIRES_ACTION && intent.status() != PaymentStatus.AUTHORIZED) {
            throw new BusinessRuleViolationException("INVALID_PAYMENT_STATE",
                    "Payment is " + intent.status() + " and cannot be confirmed");
        }

        int attemptNo = gateway.nextAttemptNo(intent.id());
        var provider = providers.get(intent.provider());
        ChargeResult result;
        try {
            result = retry.call(() -> provider.confirm(new ConfirmCommand(intent.providerIntentId(),
                    request.paymentMethodToken(), intent.idempotencyKey() + ":" + attemptNo)));
        } catch (ProviderTransientException e) {
            gateway.insertAttempt(attempt(intent, attemptNo, null, "ERROR", e.getMessage()));
            throw new DependencyUnavailableException("Payment provider unavailable. Retry the confirmation.", e);
        } catch (ProviderRejectedException e) {
            result = new ChargeResult(ChargeOutcome.DECLINED, null, e.getCode(), e.getMessage(), null);
        }

        var outcome = result;
        return new Response(gateway.inTransaction(() -> {
            gateway.insertAttempt(attempt(intent, attemptNo, outcome.providerChargeId(), outcome.outcome().name(),
                    outcome.rawResponse()));
            return switch (outcome.outcome()) {
                case SUCCEEDED -> state.markCaptured(intent);
                case DECLINED -> state.markFailed(intent, outcome.failureCode(), outcome.failureMessage());
                case REQUIRES_ACTION -> intent;
            };
        }));
    }

    private PaymentAttempt attempt(PaymentIntent intent, int attemptNo, String chargeId, String status, String raw) {
        return new PaymentAttempt(UUID.randomUUID(), intent.id(), attemptNo, chargeId, status, raw,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    }
}
