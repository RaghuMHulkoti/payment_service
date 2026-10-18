package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.exception.PaymentNotFoundException;
import com.learning.payment_service.gateway.PaymentGateway;

import java.util.List;
import java.util.UUID;

/** Status lookup. With a customerId the caller must own the payment; someone else's payment looks like 404. */
public class GetPaymentUsecase implements Usecase<GetPaymentUsecaseRequest, GetPaymentUsecaseResponse> {



    private final PaymentGateway gateway;

    public GetPaymentUsecase(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public GetPaymentUsecaseResponse execute(GetPaymentUsecaseRequest request) {
        var intent = gateway.findIntent(request.getPaymentId())
                .filter(i -> request.getCustomerId() == null || i.customerId().equals(request.getCustomerId()))
                .orElseThrow(() -> new PaymentNotFoundException(request.getPaymentId()));
        return new GetPaymentUsecaseResponse(intent, gateway.findRefundsByIntent(intent.id()));
    }
}
