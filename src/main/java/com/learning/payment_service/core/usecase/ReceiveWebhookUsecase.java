package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.domain.WebhookEvent;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import com.learning.payment_service.core.provider.WebhookHandlers;
import com.learning.payment_service.exception.InvalidWebhookException;
import com.learning.payment_service.gateway.WebhookEventGateway;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * TDD §14.4: the endpoint does only signature verification and persistence, then answers 200 so the PSP stops
 * retrying. Retries of the same event are detected by (provider, event id) and dropped. The event is applied
 * later by {@link ProcessWebhookEventsUsecase}.
 */
public class ReceiveWebhookUsecase implements Usecase<ReceiveWebhookUsecase.Request, ReceiveWebhookUsecase.Response> {

    /** Stays well under the 32767-character payload column. */
    static final int MAX_BODY_CHARS = 30_000;

    public record Request(Provider provider, WebhookRequest webhook) implements UsecaseRequest {
    }

    public record Response(boolean duplicate) implements UsecaseResponse {
    }

    private final WebhookHandlers handlers;
    private final WebhookEventGateway events;
    private final Clock clock;

    public ReceiveWebhookUsecase(WebhookHandlers handlers, WebhookEventGateway events, Clock clock) {
        this.handlers = handlers;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        var handler = handlers.get(request.provider());
        if (!handler.verify(request.webhook())) {
            throw InvalidWebhookException.badSignature("Invalid webhook signature");
        }
        String body = request.webhook().body();
        if (body.length() > MAX_BODY_CHARS) {
            throw InvalidWebhookException.malformed("Webhook body too large");
        }
        String eventId = handler.eventId(request.webhook());
        String type = handler.eventType(body);
        var stored = events.insertIfAbsent(new WebhookEvent(UUID.randomUUID(), request.provider(), eventId,
                type.length() > 60 ? type.substring(0, 60) : type, body, true, null,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)));
        return new Response(!stored);
    }
}
