package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.usecase.UsecaseResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRefundUsecaseResponse implements UsecaseResponse {
    private Refund refund;
    private PaymentIntent intent;
    private boolean created;
}
