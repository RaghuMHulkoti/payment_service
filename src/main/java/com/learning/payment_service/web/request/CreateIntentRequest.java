package com.learning.payment_service.web.request;

import com.learning.payment_service.core.domain.Provider;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateIntentRequest {
    private @NotNull UUID orderId;
    private @NotNull UUID customerId;
    private @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount;
    private @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency;
    private Provider provider;
}
