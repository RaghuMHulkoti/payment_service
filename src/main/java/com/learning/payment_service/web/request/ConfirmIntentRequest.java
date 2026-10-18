package com.learning.payment_service.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmIntentRequest(@NotBlank @Size(max = 200) String paymentMethodToken) {
}
