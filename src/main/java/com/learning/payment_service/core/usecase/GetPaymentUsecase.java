package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.exception.PaymentNotFoundException;
import com.learning.payment_service.gateway.PaymentGateway;

import java.util.List;
import java.util.UUID;

/** Status lookup. With a customerId the caller must own the payment; someone else's payment looks like 404. */
public class GetPaymentUsecase implements Usecase<GetPaymentUsecase.Request, GetPaymentUsecase.Response> {

    public record Request(UUID paymentId, UUID customerId) implements UsecaseRequest {
    }

    public record Response(PaymentIntent intent, List<Refund> refunds) implements UsecaseResponse {
    }

    private final PaymentGateway gateway;

    public GetPaymentUsecase(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Response execute(Request request) {
        var intent = gateway.findIntent(request.paymentId())
                .filter(i -> request.customerId() == null || i.customerId().equals(request.customerId()))
                .orElseThrow(() -> new PaymentNotFoundException(request.paymentId()));
        return new Response(intent, gateway.findRefundsByIntent(intent.id()));
    }
}
