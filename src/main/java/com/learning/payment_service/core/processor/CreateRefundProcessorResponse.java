package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.processor.ProcessorResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRefundProcessorResponse implements ProcessorResponse {
    private Refund refund;
    private PaymentIntent intent;
    private boolean created;
}
