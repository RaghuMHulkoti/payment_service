package com.learning.payment_service.web;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.web.response.PaymentResponse;
import com.learning.payment_service.web.response.RefundResponse;

import java.util.List;

final class PaymentResponseMapper {

    private PaymentResponseMapper() {
    }

    static PaymentResponse toResponse(PaymentIntent i, String clientSecret, List<Refund> refunds) {
        return new PaymentResponse(i.id(), i.orderId(), i.customerId(), i.amount(), i.currency(),
                i.provider().name(), i.providerIntentId(), i.status().name(), i.failureCode(), i.failureMessage(),
                clientSecret, i.createdAt(), i.updatedAt(), refunds.stream().map(PaymentResponseMapper::toResponse).toList());
    }

    static RefundResponse toResponse(Refund r) {
        return new RefundResponse(r.id(), r.intentId(), r.orderId(), r.amount(), r.currency(), r.reason(),
                r.status().name(), r.providerRefundId(), r.createdAt(), r.completedAt());
    }
}
