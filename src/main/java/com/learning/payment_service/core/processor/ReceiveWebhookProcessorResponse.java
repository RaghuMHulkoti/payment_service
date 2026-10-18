package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.processor.ProcessorResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveWebhookProcessorResponse implements ProcessorResponse {
    private boolean duplicate;
}
