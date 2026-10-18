package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.usecase.GetPaymentUsecase;
import com.learning.payment_service.core.usecase.GetPaymentUsecaseRequest;

public class GetPaymentProcessor implements Processor<GetPaymentProcessorRequest, GetPaymentProcessorResponse> {

    private final GetPaymentUsecase usecase;

    public GetPaymentProcessor(GetPaymentUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public GetPaymentProcessorResponse process(GetPaymentProcessorRequest request) {
        var result = usecase.execute(new GetPaymentUsecaseRequest(request.getPaymentId(), request.getCustomerId()));
        return new GetPaymentProcessorResponse(result.getIntent(), result.getRefunds());
    }
}
