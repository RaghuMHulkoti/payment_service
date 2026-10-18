package com.learning.payment_service.core.service.outbox;

import com.learning.payment_service.core.service.ServiceRequest;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxServiceRequest implements ServiceRequest {
    private UUID aggregateId;
    private String eventType;
    private Map<String, Object> data;
}
