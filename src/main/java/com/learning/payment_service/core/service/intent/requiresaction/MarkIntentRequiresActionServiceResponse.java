package com.learning.payment_service.core.service.intent.requiresaction;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.service.ServiceResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkIntentRequiresActionServiceResponse implements ServiceResponse {
    private PaymentIntent intent;
}
