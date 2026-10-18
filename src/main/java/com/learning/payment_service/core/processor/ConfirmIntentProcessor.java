package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.usecase.ConfirmIntentUsecase;
import com.learning.payment_service.core.usecase.ConfirmIntentUsecaseRequest;

public class ConfirmIntentProcessor implements Processor<ConfirmIntentProcessorRequest, ConfirmIntentProcessorResponse> {

    private final ConfirmIntentUsecase usecase;

    public ConfirmIntentProcessor(ConfirmIntentUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public ConfirmIntentProcessorResponse process(ConfirmIntentProcessorRequest request) {
        return new ConfirmIntentProcessorResponse(usecase.execute(
                new ConfirmIntentUsecaseRequest(request.getIntentId(), request.getPaymentMethodToken())).getIntent());
    }
}
