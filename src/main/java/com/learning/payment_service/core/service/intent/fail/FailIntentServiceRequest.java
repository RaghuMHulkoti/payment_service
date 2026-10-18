package com.learning.payment_service.core.service.intent.fail;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.service.ServiceRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailIntentServiceRequest implements ServiceRequest {
    private PaymentIntent intent;
    private String code;
    private String message;
}
