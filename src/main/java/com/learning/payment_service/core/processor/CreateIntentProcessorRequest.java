package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.domain.Provider;
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
public class CreateIntentProcessorRequest implements ProcessorRequest {
    private String idempotencyKey;
    private UUID orderId;
    private UUID customerId;
    private BigDecimal amount;
    private String currency;
    private Provider provider;
}
