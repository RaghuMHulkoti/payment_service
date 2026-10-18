package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.usecase.UsecaseRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessWebhookEventsUsecaseRequest implements UsecaseRequest {
    private int limit;
}
