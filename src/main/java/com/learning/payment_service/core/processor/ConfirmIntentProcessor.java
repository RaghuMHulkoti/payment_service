package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.usecase.ConfirmIntentUsecase;

import java.util.UUID;

public class ConfirmIntentProcessor
        implements Processor<ConfirmIntentProcessor.Request, ConfirmIntentProcessor.Response> {

    public record Request(UUID intentId, String paymentMethodToken) implements ProcessorRequest {
    }

    public record Response(PaymentIntent intent) implements ProcessorResponse {
    }

    private final ConfirmIntentUsecase usecase;

    public ConfirmIntentProcessor(ConfirmIntentUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public Response process(Request request) {
        return new Response(usecase.execute(
                new ConfirmIntentUsecase.Request(request.intentId(), request.paymentMethodToken())).intent());
    }
}
