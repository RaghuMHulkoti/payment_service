package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.usecase.CreateRefundUsecase;
import com.learning.payment_service.core.usecase.CreateRefundUsecaseRequest;

public class CreateRefundProcessor implements Processor<CreateRefundProcessorRequest, CreateRefundProcessorResponse> {

    private final CreateRefundUsecase usecase;

    public CreateRefundProcessor(CreateRefundUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public CreateRefundProcessorResponse process(CreateRefundProcessorRequest request) {
        var result = usecase.execute(new CreateRefundUsecaseRequest(request.getIdempotencyKey(),
                request.getOrderId(), request.getAmount(), request.getReason()));
        return new CreateRefundProcessorResponse(result.getRefund(), result.getIntent(), result.isCreated());
    }
}
