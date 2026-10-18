package com.learning.payment_service.web.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code clientSecret} is only present on intent creation: the client uses it to confirm with the PSP. */
public record PaymentResponse(UUID paymentId, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                              String provider, String providerIntentId, String status, String failureCode,
                              String failureMessage, String clientSecret, OffsetDateTime createdAt,
                              OffsetDateTime updatedAt, List<RefundResponse> refunds) {
}
