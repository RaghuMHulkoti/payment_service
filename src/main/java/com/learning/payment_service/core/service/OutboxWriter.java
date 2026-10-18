package com.learning.payment_service.core.service;

import com.learning.payment_service.gateway.PaymentGateway;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes domain events to {@code payment_outbox} in the caller's transaction (transactional outbox, ADR).
 * Envelope follows TDD §13.3. A relay to the message bus reads PENDING rows; none runs in this service yet.
 */
public class OutboxWriter {

    private static final String PRODUCER = "payment-service";

    private final PaymentGateway gateway;
    private final JsonMapper mapper;
    private final Clock clock;

    public OutboxWriter(PaymentGateway gateway, JsonMapper mapper, Clock clock) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void emit(UUID aggregateId, String eventType, Map<String, Object> data) {
        var now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", now.toString());
        envelope.put("producer", PRODUCER);
        envelope.put("version", 1);
        envelope.put("data", data);
        gateway.insertOutbox(aggregateId, eventType, mapper.writeValueAsString(envelope), now);
    }
}
