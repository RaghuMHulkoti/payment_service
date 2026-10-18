package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.service.intent.cancel.CancelIntentService;
import com.learning.payment_service.core.service.intent.cancel.CancelIntentServiceRequest;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentService;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentServiceRequest;
import com.learning.payment_service.core.service.intent.fail.FailIntentService;
import com.learning.payment_service.core.service.intent.fail.FailIntentServiceRequest;
import com.learning.payment_service.core.provider.ProviderRegistry;
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
        implements Usecase<ReconcileStuckIntentsUsecaseRequest, ReconcileStuckIntentsUsecaseResponse> {

    private static final Logger log = LoggerFactory.getLogger(ReconcileStuckIntentsUsecase.class);



    private final PaymentGateway gateway;
    private final ProviderRegistry providers;
    private final CancelIntentService cancelIntentService;
    private final CaptureIntentService captureIntentService;
    private final FailIntentService failIntentService;
    private final Clock clock;

    public ReconcileStuckIntentsUsecase(PaymentGateway gateway, ProviderRegistry providers, CancelIntentService cancelIntentService, CaptureIntentService captureIntentService, FailIntentService failIntentService, Clock clock) {
        this.gateway = gateway;
        this.providers = providers;
        this.cancelIntentService = cancelIntentService;
        this.captureIntentService = captureIntentService;
        this.failIntentService = failIntentService;
        this.clock = clock;
    }

    @Override
    public ReconcileStuckIntentsUsecaseResponse execute(ReconcileStuckIntentsUsecaseRequest request) {
        var cutoff = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).minus(request.getStuckAfter());
        int reconciled = 0;
        for (var intent : gateway.findStuckIntents(cutoff, request.getLimit())) {
            try {
                if (intent.providerIntentId() == null) {
                    failIntentService.execute(FailIntentServiceRequest.builder().intent(intent).code("INTENT_CREATION_TIMEOUT").message("The payment provider never confirmed this intent").build());
                    reconciled++;
                    continue;
                }
                var status = providers.get(intent.provider()).fetchStatus(intent.providerIntentId());
                if (status.isEmpty()) {
                    continue;
                }
                switch (status.get()) {
                    case SUCCEEDED -> captureIntentService.execute(CaptureIntentServiceRequest.builder().intent(intent).build()).getIntent();
                    case FAILED -> failIntentService.execute(FailIntentServiceRequest.builder().intent(intent).code("PROVIDER_REPORTED_FAILURE").message("The payment provider reports this payment failed").build()).getIntent();
                    case CANCELLED -> cancelIntentService.execute(CancelIntentServiceRequest.builder().intent(intent).build()).getIntent();
                    case PENDING -> {
                        continue;
                    }
                }
                reconciled++;
            } catch (RuntimeException e) {
                log.warn("Reconciling payment {} failed: {}", intent.id(), e.getMessage());
            }
        }
        return new ReconcileStuckIntentsUsecaseResponse(reconciled);
    }
}
