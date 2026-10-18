package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.processor.ProcessorResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateIntentProcessorResponse implements ProcessorResponse {
    private PaymentIntent intent;
    private String clientSecret;
    private boolean created;
}
