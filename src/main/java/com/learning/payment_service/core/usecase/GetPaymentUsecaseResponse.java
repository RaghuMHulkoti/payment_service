package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Refund;
import com.learning.payment_service.core.usecase.UsecaseResponse;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetPaymentUsecaseResponse implements UsecaseResponse {
    private PaymentIntent intent;
    private List<Refund> refunds;
}
