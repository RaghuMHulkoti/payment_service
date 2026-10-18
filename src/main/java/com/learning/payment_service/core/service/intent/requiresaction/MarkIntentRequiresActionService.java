package com.learning.payment_service.core.service.intent.requiresaction;

import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.service.Service;
import com.learning.payment_service.core.service.ServiceSupport;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;

/** Provider accepted the intent; the customer must now confirm with the client secret. */
public class MarkIntentRequiresActionService
        implements Service<MarkIntentRequiresActionServiceRequest, MarkIntentRequiresActionServiceResponse> {

    private final PaymentGateway gateway;
    private final Clock clock;

    public MarkIntentRequiresActionService(PaymentGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Override
    public MarkIntentRequiresActionServiceResponse execute(MarkIntentRequiresActionServiceRequest request) {
        var updated = gateway.updateIntent(request.getIntent().withProviderIntent(
                request.getProviderIntentId(), PaymentStatus.REQUIRES_ACTION, ServiceSupport.now(clock)));
        return new MarkIntentRequiresActionServiceResponse(updated);
    }
}
