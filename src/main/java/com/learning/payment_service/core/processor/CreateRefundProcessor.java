package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.usecase.CreateRefundUsecase;

import java.math.BigDecimal;
import java.util.UUID;

public class CreateRefundProcessor
        implements Processor<CreateRefundProcessor.Request, CreateRefundProcessor.Response> {

    public record Request(String idempotencyKey, UUID orderId, BigDecimal amount, String reason)
            implements ProcessorRequest {
    }

    public record Response(Refund refund, PaymentIntent intent, boolean created) implements ProcessorResponse {
    }

    private final CreateRefundUsecase usecase;

    public CreateRefundProcessor(CreateRefundUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public Response process(Request request) {
        var result = usecase.execute(new CreateRefundUsecase.Request(request.idempotencyKey(), request.orderId(),
                request.amount(), request.reason()));
        return new Response(result.refund(), result.intent(), result.created());
    }
}
