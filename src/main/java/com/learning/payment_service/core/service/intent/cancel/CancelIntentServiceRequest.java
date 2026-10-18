package com.learning.payment_service.core.service.intent.cancel;

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
public class CancelIntentServiceRequest implements ServiceRequest {
    private PaymentIntent intent;
}
