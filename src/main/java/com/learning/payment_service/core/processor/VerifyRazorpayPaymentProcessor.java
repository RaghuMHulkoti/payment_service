package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.usecase.VerifyRazorpayPaymentUsecase;

import java.util.UUID;

public class VerifyRazorpayPaymentProcessor
        implements Processor<VerifyRazorpayPaymentProcessor.Request, VerifyRazorpayPaymentProcessor.Response> {

    public record Request(UUID paymentId, UUID customerId, String razorpayOrderId, String razorpayPaymentId,
                          String razorpaySignature) implements ProcessorRequest {
    }

    public record Response(PaymentIntent intent) implements ProcessorResponse {
    }

    private final VerifyRazorpayPaymentUsecase usecase;

    public VerifyRazorpayPaymentProcessor(VerifyRazorpayPaymentUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public Response process(Request request) {
        var result = usecase.execute(new VerifyRazorpayPaymentUsecase.Request(request.paymentId(), request.customerId(),
                request.razorpayOrderId(), request.razorpayPaymentId(), request.razorpaySignature()));
        return new Response(result.intent());
    }
}
