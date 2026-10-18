package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import com.learning.payment_service.core.usecase.ReceiveWebhookUsecase;

public class ReceiveWebhookProcessor
        implements Processor<ReceiveWebhookProcessor.Request, ReceiveWebhookProcessor.Response> {

    public record Request(Provider provider, WebhookRequest webhook) implements ProcessorRequest {
    }

    public record Response(boolean duplicate) implements ProcessorResponse {
    }

    private final ReceiveWebhookUsecase usecase;

    public ReceiveWebhookProcessor(ReceiveWebhookUsecase usecase) {
        this.usecase = usecase;
    }

    @Override
    public Response process(Request request) {
        return new Response(usecase.execute(
                new ReceiveWebhookUsecase.Request(request.provider(), request.webhook())).duplicate());
    }
}
