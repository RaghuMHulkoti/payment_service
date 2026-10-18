package com.learning.payment_service.core.service.refund.fail;

import com.learning.payment_service.core.service.Service;
import com.learning.payment_service.core.service.ServiceSupport;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;

public class FailRefundService implements Service<FailRefundServiceRequest, FailRefundServiceResponse> {

    private final PaymentGateway gateway;
    private final Clock clock;

    public FailRefundService(PaymentGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Override
    public FailRefundServiceResponse execute(FailRefundServiceRequest request) {
        return new FailRefundServiceResponse(
                gateway.updateRefund(request.getRefund().failed(ServiceSupport.now(clock))));
    }
}
