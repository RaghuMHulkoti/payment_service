package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.usecase.UsecaseRequest;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRefundUsecaseRequest implements UsecaseRequest {
    private String idempotencyKey;
    private UUID orderId;
    private BigDecimal amount;
    private String reason;
}
