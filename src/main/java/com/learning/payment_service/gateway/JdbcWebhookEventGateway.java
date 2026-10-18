package com.learning.payment_service.gateway;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.domain.WebhookEvent;
import com.learning.payment_service.exception.DependencyUnavailableException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.learning.payment_service.gateway.JdbcSupport.raw;
import static com.learning.payment_service.gateway.JdbcSupport.time;
import static com.learning.payment_service.gateway.JdbcSupport.uuid;

public class JdbcWebhookEventGateway implements WebhookEventGateway {

    private static final RowMapper<WebhookEvent> EVENT = (rs, n) -> new WebhookEvent(
            uuid(rs, "id"), Provider.valueOf(rs.getString("provider")), rs.getString("provider_event_id"),
            rs.getString("event_type"), rs.getString("payload"), rs.getBoolean("signature_valid"),
            time(rs, "processed_at"), time(rs, "received_at"));

    private final JdbcTemplate jdbc;

    public JdbcWebhookEventGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(WebhookEvent e) {
        try {
            guard("store webhook", () -> jdbc.update("""
                    INSERT INTO webhook_events (id, provider, provider_event_id, event_type, payload, signature_valid,
                                                processed_at, received_at)
                    VALUES (?,?,?,?,?,?,?,?)""",
                    raw(e.id()), e.provider().name(), e.providerEventId(), e.eventType(), e.payload(),
                    e.signatureValid(), e.processedAt(), e.receivedAt()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public List<WebhookEvent> findUnprocessed(int limit) {
        return guard("read webhooks", () -> jdbc.query("""
                SELECT id, provider, provider_event_id, event_type, payload, signature_valid, processed_at, received_at
                FROM webhook_events WHERE processed_at IS NULL ORDER BY received_at FETCH FIRST ? ROWS ONLY""",
                EVENT, limit));
    }

    @Override
    public void markProcessed(UUID id, OffsetDateTime processedAt) {
        guard("update webhook", () -> jdbc.update(
                "UPDATE webhook_events SET processed_at = ? WHERE id = ?", processedAt, raw(id)));
    }

    private <T> T guard(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (DuplicateKeyException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Payment store unavailable: could not " + action, e);
        }
    }
}
