package com.learning.payment_service.web.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundResponse(UUID refundId, UUID paymentId, UUID orderId, BigDecimal amount, String currency,
                             String reason, String status, String providerRefundId, OffsetDateTime createdAt,
                             OffsetDateTime completedAt) {
}
