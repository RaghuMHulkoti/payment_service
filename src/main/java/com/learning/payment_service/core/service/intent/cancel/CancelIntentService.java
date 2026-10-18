package com.learning.payment_service.core.service.intent.cancel;

import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.service.Service;
import com.learning.payment_service.core.service.ServiceSupport;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.outbox.OutboxServiceRequest;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;

/** Moves an intent to CANCELLED and emits PAYMENT_FAILED (code CANCELLED) in the same transaction. */
public class CancelIntentService implements Service<CancelIntentServiceRequest, CancelIntentServiceResponse> {

    private final PaymentGateway gateway;
    private final OutboxService outboxService;
    private final Clock clock;

    public CancelIntentService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        this.gateway = gateway;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Override
    public CancelIntentServiceResponse execute(CancelIntentServiceRequest request) {
        var updated = gateway.inTransaction(() -> {
            var saved = gateway.updateIntent(
                    request.getIntent().withStatus(PaymentStatus.CANCELLED, ServiceSupport.now(clock)));
            var data = ServiceSupport.intentEventData(saved);
            data.put("failureCode", "CANCELLED");
            outboxService.execute(OutboxServiceRequest.builder()
                    .aggregateId(saved.id()).eventType("PAYMENT_FAILED").data(data).build());
            return saved;
        });
        return new CancelIntentServiceResponse(updated);
    }
}
