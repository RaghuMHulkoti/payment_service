package com.learning.payment_service.web.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {@code clientSecret} is only present on intent creation: the client uses it to confirm with the PSP. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private UUID paymentId;
    private UUID orderId;
    private UUID customerId;
    private BigDecimal amount;
    private String currency;
    private String provider;
    private String providerIntentId;
    private String status;
    private String failureCode;
    private String failureMessage;
    private String clientSecret;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<RefundResponse> refunds;
}
