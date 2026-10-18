package com.learning.payment_service.core.service.intent.fail;

import com.learning.payment_service.core.service.Service;
import com.learning.payment_service.core.service.ServiceSupport;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.outbox.OutboxServiceRequest;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;

/** Moves an intent to FAILED and emits PAYMENT_FAILED in the same transaction. */
public class FailIntentService implements Service<FailIntentServiceRequest, FailIntentServiceResponse> {

    private final PaymentGateway gateway;
    private final OutboxService outboxService;
    private final Clock clock;

    public FailIntentService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        this.gateway = gateway;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Override
    public FailIntentServiceResponse execute(FailIntentServiceRequest request) {
        var updated = gateway.inTransaction(() -> {
            var saved = gateway.updateIntent(request.getIntent()
                    .failed(request.getCode(), request.getMessage(), ServiceSupport.now(clock)));
            var data = ServiceSupport.intentEventData(saved);
            data.put("failureCode", saved.failureCode());
            data.put("failureMessage", saved.failureMessage());
            outboxService.execute(OutboxServiceRequest.builder()
                    .aggregateId(saved.id()).eventType("PAYMENT_FAILED").data(data).build());
            return saved;
        });
        return new FailIntentServiceResponse(updated);
    }
}
