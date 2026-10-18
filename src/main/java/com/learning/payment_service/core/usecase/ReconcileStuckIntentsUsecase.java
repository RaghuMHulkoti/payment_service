package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.service.PaymentStateService;
import com.learning.payment_service.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * TDD §14.6: intents stuck in INITIATED / REQUIRES_ACTION for longer than {@code stuckAfter} are checked against
 * the PSP's canonical status. An intent that never got a provider id is failed (the PSP never heard of it).
 */
public class ReconcileStuckIntentsUsecase
        implements Usecase<ReconcileStuckIntentsUsecase.Request, ReconcileStuckIntentsUsecase.Response> {

    private static final Logger log = LoggerFactory.getLogger(ReconcileStuckIntentsUsecase.class);

    public record Request(Duration stuckAfter, int limit) implements UsecaseRequest {
    }

    public record Response(int reconciled) implements UsecaseResponse {
    }

    private final PaymentGateway gateway;
    private final ProviderRegistry providers;
    private final PaymentStateService state;
    private final Clock clock;

    public ReconcileStuckIntentsUsecase(PaymentGateway gateway, ProviderRegistry providers,
                                        PaymentStateService state, Clock clock) {
        this.gateway = gateway;
        this.providers = providers;
        this.state = state;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        var cutoff = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(request.stuckAfter());
        int reconciled = 0;
        for (var intent : gateway.findStuckIntents(cutoff, request.limit())) {
            try {
                if (intent.providerIntentId() == null) {
                    state.markFailed(intent, "INTENT_CREATION_TIMEOUT",
                            "The payment provider never confirmed this intent");
                    reconciled++;
                    continue;
                }
                var status = providers.get(intent.provider()).fetchStatus(intent.providerIntentId());
                if (status.isEmpty()) {
                    continue;
                }
                switch (status.get()) {
                    case SUCCEEDED -> state.markCaptured(intent);
                    case FAILED -> state.markFailed(intent, "PROVIDER_REPORTED_FAILURE",
                            "The payment provider reports this payment failed");
                    case CANCELLED -> state.markCancelled(intent);
                    case PENDING -> {
                        continue;
                    }
                }
                reconciled++;
            } catch (RuntimeException e) {
                log.warn("Reconciling payment {} failed: {}", intent.id(), e.getMessage());
            }
        }
        return new Response(reconciled);
    }
}
