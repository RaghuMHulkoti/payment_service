package com.learning.payment_service.gateway;

import com.learning.payment_service.core.domain.WebhookEvent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface WebhookEventGateway {

    /** Stores the event; returns false if (provider, providerEventId) was already stored, i.e. a PSP retry. */
    boolean insertIfAbsent(WebhookEvent event);

    List<WebhookEvent> findUnprocessed(int limit);

    void markProcessed(UUID id, OffsetDateTime processedAt);
}
