package com.learning.payment_service.gateway;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.domain.WebhookEvent;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.gateway.entity.WebhookEventEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class JpaWebhookEventGateway implements WebhookEventGateway {

    private final WebhookEventRepository repository;

    public JpaWebhookEventGateway(WebhookEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean insertIfAbsent(WebhookEvent e) {
        try {
            guard("store webhook", () -> repository.saveAndFlush(WebhookEventEntity.builder().id(e.id())
                    .provider(e.provider().name()).providerEventId(e.providerEventId()).eventType(e.eventType())
                    .payload(e.payload()).signatureValid(e.signatureValid()).processedAt(e.processedAt())
                    .receivedAt(e.receivedAt()).build()));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }

    @Override
    public List<WebhookEvent> findUnprocessed(int limit) {
        return guard("read webhooks", () -> repository.findByProcessedAtIsNullOrderByReceivedAt(Limit.of(limit)).stream()
                .map(JpaWebhookEventGateway::toDomain).toList());
    }

    @Override
    public void markProcessed(UUID id, OffsetDateTime processedAt) {
        guard("update webhook", () -> repository.markProcessed(id, processedAt));
    }

    private static WebhookEvent toDomain(WebhookEventEntity e) {
        return new WebhookEvent(e.getId(), Provider.valueOf(e.getProvider()), e.getProviderEventId(), e.getEventType(),
                e.getPayload(), e.isSignatureValid(), JpaPaymentGateway.utc(e.getProcessedAt()),
                JpaPaymentGateway.utc(e.getReceivedAt()));
    }

    private <T> T guard(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataIntegrityViolationException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Payment store unavailable: could not " + action, e);
        }
    }
}
