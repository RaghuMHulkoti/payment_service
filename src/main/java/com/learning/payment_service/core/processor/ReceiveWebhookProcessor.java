package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.usecase.ReceiveWebhookUsecase;
import com.learning.payment_service.core.usecase.ReceiveWebhookUsecaseRequest;

public class ReceiveWebhookProcessor implements Processor<ReceiveWebhookProcessorRequest, ReceiveWebhookProcessorResponse> {

    private final ReceiveWebhookUsecase usecase;

    public ReceiveWebhookProcessor(ReceiveWebhookUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public ReceiveWebhookProcessorResponse process(ReceiveWebhookProcessorRequest request) {
        return new ReceiveWebhookProcessorResponse(usecase.execute(
                new ReceiveWebhookUsecaseRequest(request.getProvider(), request.getWebhook())).isDuplicate());
    }
}
