package com.learning.payment_service.web.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundResponse {
    private UUID refundId;
    private UUID paymentId;
    private UUID orderId;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private String status;
    private String providerRefundId;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;
}
