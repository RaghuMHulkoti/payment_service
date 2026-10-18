package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.usecase.CreateIntentUsecase;
import com.learning.payment_service.core.usecase.CreateIntentUsecaseRequest;

public class CreateIntentProcessor implements Processor<CreateIntentProcessorRequest, CreateIntentProcessorResponse> {

    private final CreateIntentUsecase usecase;

    public CreateIntentProcessor(CreateIntentUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public CreateIntentProcessorResponse process(CreateIntentProcessorRequest request) {
        var result = usecase.execute(new CreateIntentUsecaseRequest(request.getIdempotencyKey(),
                request.getOrderId(), request.getCustomerId(), request.getAmount(), request.getCurrency(),
                request.getProvider()));
        return new CreateIntentProcessorResponse(result.getIntent(), result.getClientSecret(), result.isCreated());
    }
}
