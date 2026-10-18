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
public class ConfirmIntentProcessorRequest implements ProcessorRequest {
    private UUID intentId;
    private String paymentMethodToken;
}
