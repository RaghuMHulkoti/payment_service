package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.usecase.GetCheckoutUsecase;

public class GetCheckoutProcessor implements Processor<GetCheckoutProcessor.Request, GetCheckoutProcessor.Response> {

    public record Request(java.util.UUID paymentId, java.util.UUID customerId) implements ProcessorRequest {
    }

    public record Response(GetCheckoutUsecase.Response checkout) implements ProcessorResponse {
    }

    private final GetCheckoutUsecase usecase;

    public GetCheckoutProcessor(GetCheckoutUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public Response process(Request request) {
        return new Response(usecase.execute(new GetCheckoutUsecase.Request(request.paymentId(), request.customerId())));
    }
}
