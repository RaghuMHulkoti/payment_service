package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import com.learning.payment_service.core.usecase.UsecaseRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveWebhookUsecaseRequest implements UsecaseRequest {
    private Provider provider;
    private WebhookRequest webhook;
}
