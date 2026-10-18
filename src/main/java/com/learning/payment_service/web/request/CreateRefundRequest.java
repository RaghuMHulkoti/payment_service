package com.learning.payment_service.web.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code amount} is optional: omitted means "refund whatever is still refundable". */
public record CreateRefundRequest(
        @NotNull UUID orderId,
        @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Size(max = 200) String reason) {
}
