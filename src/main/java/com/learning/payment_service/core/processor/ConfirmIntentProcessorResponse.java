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
public class ConfirmIntentProcessorResponse implements ProcessorResponse {
    private PaymentIntent intent;
}
