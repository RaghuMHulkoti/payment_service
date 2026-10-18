package com.learning.payment_service.core.processor;

import com.learning.payment_service.core.processor.ProcessorRequest;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetPaymentProcessorRequest implements ProcessorRequest {
    private UUID paymentId;
    private UUID customerId;
}
