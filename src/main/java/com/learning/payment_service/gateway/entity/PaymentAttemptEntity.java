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
@Table(name = "payment_attempts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAttemptEntity {

    @Id
    private UUID id;

    @Column(name = "intent_id", nullable = false)
    private UUID intentId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "provider_charge_id")
    private String providerChargeId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "raw_response")
    private String rawResponse;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
