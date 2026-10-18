package com.learning.payment_service.core.service.intent.capture;

import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.service.Service;
import com.learning.payment_service.core.service.ServiceSupport;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.outbox.OutboxServiceRequest;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;

/** Moves an intent to CAPTURED and emits PAYMENT_SUCCESS in the same transaction. */
public class CaptureIntentService implements Service<CaptureIntentServiceRequest, CaptureIntentServiceResponse> {

    private final PaymentGateway gateway;
    private final OutboxService outboxService;
    private final Clock clock;

    public CaptureIntentService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        this.gateway = gateway;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Override
    public CaptureIntentServiceResponse execute(CaptureIntentServiceRequest request) {
        var updated = gateway.inTransaction(() -> {
            var now = ServiceSupport.now(clock);
            var saved = gateway.updateIntent(request.getIntent().withStatus(PaymentStatus.CAPTURED, now));
            var data = ServiceSupport.intentEventData(saved);
            data.put("capturedAt", now.toString());
            outboxService.execute(OutboxServiceRequest.builder()
                    .aggregateId(saved.id()).eventType("PAYMENT_SUCCESS").data(data).build());
            return saved;
        });
        return new CaptureIntentServiceResponse(updated);
    }
}
