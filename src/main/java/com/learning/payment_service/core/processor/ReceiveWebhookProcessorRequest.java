package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.processor.ProcessorRequest;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveWebhookProcessorRequest implements ProcessorRequest {
    private Provider provider;
    private WebhookRequest webhook;
}
