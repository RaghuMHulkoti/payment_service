package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.processor.ProcessorRequest;
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
public class CreateRefundProcessorRequest implements ProcessorRequest {
    private String idempotencyKey;
    private UUID orderId;
    private BigDecimal amount;
    private String reason;
}
