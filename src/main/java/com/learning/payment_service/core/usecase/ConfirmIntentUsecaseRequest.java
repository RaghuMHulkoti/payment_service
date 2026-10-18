package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.usecase.UsecaseRequest;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmIntentUsecaseRequest implements UsecaseRequest {
    private UUID intentId;
    private String paymentMethodToken;
}
