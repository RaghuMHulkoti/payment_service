package com.learning.payment_service.web.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** {@code amount} is optional: omitted means "refund whatever is still refundable". */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRefundRequest {
    private @NotNull UUID orderId;
    private @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount;
    private @Size(max = 200) String reason;
}
