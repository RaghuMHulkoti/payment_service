package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.usecase.CreateIntentUsecase;

import java.math.BigDecimal;
import java.util.UUID;

public class CreateIntentProcessor
        implements Processor<CreateIntentProcessor.Request, CreateIntentProcessor.Response> {

    public record Request(String idempotencyKey, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                          Provider provider) implements ProcessorRequest {
    }

    public record Response(PaymentIntent intent, String clientSecret, boolean created) implements ProcessorResponse {
    }

    private final CreateIntentUsecase usecase;

    public CreateIntentProcessor(CreateIntentUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public Response process(Request request) {
        var result = usecase.execute(new CreateIntentUsecase.Request(request.idempotencyKey(), request.orderId(),
                request.customerId(), request.amount(), request.currency(), request.provider()));
        return new Response(result.intent(), result.clientSecret(), result.created());
    }
}
