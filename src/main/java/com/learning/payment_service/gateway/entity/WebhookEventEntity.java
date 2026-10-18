package com.learning.payment_service.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEventEntity {

    @Id
    private UUID id;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
}
