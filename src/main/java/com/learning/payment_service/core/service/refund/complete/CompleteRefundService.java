package com.learning.payment_service.core.service.refund.complete;

import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.service.Service;
import com.learning.payment_service.core.service.ServiceSupport;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.outbox.OutboxServiceRequest;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;
import java.util.LinkedHashMap;

/** Money moved back: mark the refund SUCCESS and the intent REFUNDED or PARTIALLY_REFUNDED. */
public class CompleteRefundService implements Service<CompleteRefundServiceRequest, CompleteRefundServiceResponse> {

    private final PaymentGateway gateway;
    private final OutboxService outboxService;
    private final Clock clock;

    public CompleteRefundService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        this.gateway = gateway;
        this.outboxService = outboxService;
        this.clock = clock;
    }

    @Override
    public CompleteRefundServiceResponse execute(CompleteRefundServiceRequest request) {
        var result = gateway.inTransaction(() -> {
            var now = ServiceSupport.now(clock);
            var done = gateway.updateRefund(request.getRefund().succeeded(request.getProviderRefundId(), now));
            var intent = gateway.findIntent(done.intentId()).orElseThrow();
            if (intent.status().isRefundable()) {
                var refunded = gateway.sumSucceededRefunds(intent.id());
                var status = refunded.compareTo(intent.amount()) >= 0
                        ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
                gateway.updateIntent(intent.withStatus(status, now));
            }
            var data = new LinkedHashMap<String, Object>();
            data.put("orderId", done.orderId().toString());
            data.put("refundId", done.id().toString());
            data.put("amount", done.amount());
            data.put("completedAt", now.toString());
            outboxService.execute(OutboxServiceRequest.builder()
                    .aggregateId(done.id()).eventType("REFUND_COMPLETED").data(data).build());
            return done;
        });
        return new CompleteRefundServiceResponse(result);
    }
}
