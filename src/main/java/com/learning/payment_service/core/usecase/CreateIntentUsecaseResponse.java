package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.usecase.UsecaseResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateIntentUsecaseResponse implements UsecaseResponse {
    private PaymentIntent intent;
    private String clientSecret;
    private boolean created;
}
