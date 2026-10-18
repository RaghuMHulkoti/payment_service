package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.usecase.GetPaymentUsecase;

import java.util.List;
import java.util.UUID;

public class GetPaymentProcessor implements Processor<GetPaymentProcessor.Request, GetPaymentProcessor.Response> {

    public record Request(UUID paymentId, UUID customerId) implements ProcessorRequest {
    }

    public record Response(PaymentIntent intent, List<Refund> refunds) implements ProcessorResponse {
    }

    private final GetPaymentUsecase usecase;

    public GetPaymentProcessor(GetPaymentUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public Response process(Request request) {
        var result = usecase.execute(new GetPaymentUsecase.Request(request.paymentId(), request.customerId()));
        return new Response(result.intent(), result.refunds());
    }
}
