package com.learning.payment_service.core.service.outbox;

import com.learning.payment_service.core.service.Service;
import com.learning.payment_service.core.service.ServiceSupport;
import com.learning.payment_service.gateway.PaymentGateway;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Writes domain events to {@code payment_outbox} in the caller's transaction (transactional outbox, ADR).
 * Envelope follows TDD §13.3. A relay to the message bus reads PENDING rows; none runs in this service yet.
 */
public class OutboxService implements Service<OutboxServiceRequest, OutboxServiceResponse> {

    private static final String PRODUCER = "payment-service";

    private final PaymentGateway gateway;
    private final JsonMapper mapper;
    private final Clock clock;

    public OutboxService(PaymentGateway gateway, JsonMapper mapper, Clock clock) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public OutboxServiceResponse execute(OutboxServiceRequest request) {
        var now = ServiceSupport.now(clock);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", request.getEventType());
        envelope.put("occurredAt", now.toString());
        envelope.put("producer", PRODUCER);
        envelope.put("version", 1);
        envelope.put("data", request.getData());
        gateway.insertOutbox(request.getAggregateId(), request.getEventType(),
                mapper.writeValueAsString(envelope), now);
        return new OutboxServiceResponse();
    }
}
