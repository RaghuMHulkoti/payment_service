package com.learning.payment_service.gateway;

import com.learning.payment_service.gateway.entity.PaymentOutboxEntity;
import com.learning.payment_service.gateway.messaging.EventPublisher;
import com.learning.payment_service.gateway.messaging.NonRetryablePublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Publishes PENDING {@code payment_outbox} rows (TDD §13.8, §20.6).
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED} lets several replicas poll without double-sending.</li>
 *   <li>A failed publish leaves the row PENDING and stops the batch, so a broker outage loses nothing
 *       and per-order order is kept.</li>
 *   <li>Only an event the broker will never accept is marked FAILED and skipped.</li>
 * </ul>
 * The partition key is the order id inside the event, so Order Service sees one order's events in sequence.
 */
public class PaymentOutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxPoller.class);

    private final PaymentOutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Clock clock;
    private final int batchSize;

    public PaymentOutboxPoller(PaymentOutboxRepository outbox, EventPublisher publisher, TransactionTemplate tx,
                               JsonMapper json, Clock clock, int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval:1s}")
    public void run() {
        try {
            int published = runOnce();
            if (published > 0) {
                log.debug("Outbox published {} event(s)", published);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox poll failed: {}", e.getMessage());
        }
    }

    /** One locked batch in its own transaction; returns how many events were sent. */
    public int runOnce() {
        Integer published = tx.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    int publishBatch() {
        int published = 0;
        for (PaymentOutboxEntity row : outbox.lockPendingBatch(batchSize)) {
            try {
                publisher.publish(keyOf(row), row.getEventType(), row.getPayload());
                row.setStatus("SENT");
                row.setSentAt(OffsetDateTime.now(clock));
                row.setLastError(null);
                published++;
            } catch (NonRetryablePublishException e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(truncate(e.getMessage()));
                row.setStatus("FAILED");
                log.error("Outbox event {} ({}) moved to FAILED: {}", row.getId(), row.getEventType(), e.getMessage());
            } catch (RuntimeException e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setLastError(truncate(e.getMessage()));
                log.warn("Outbox event {} publish failed (attempt {}), will retry: {}", row.getId(), row.getAttempts(),
                        e.getMessage());
                break;
            }
        }
        return published;
    }

    private String keyOf(PaymentOutboxEntity row) {
        try {
            var orderId = json.readTree(row.getPayload()).path("data").path("orderId").asString("");
            return orderId.isEmpty() ? row.getAggregateId().toString() : orderId;
        } catch (RuntimeException e) {
            return row.getAggregateId().toString();
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
