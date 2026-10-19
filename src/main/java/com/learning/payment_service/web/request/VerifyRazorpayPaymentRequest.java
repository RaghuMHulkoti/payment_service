package com.learning.payment_service.web.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The three values Razorpay Checkout hands the browser after a successful payment. */
public record VerifyRazorpayPaymentRequest(
        @JsonProperty("razorpay_order_id") @NotBlank @Size(max = 100) String razorpayOrderId,
        @JsonProperty("razorpay_payment_id") @NotBlank @Size(max = 100) String razorpayPaymentId,
        @JsonProperty("razorpay_signature") @NotBlank @Size(max = 200) String razorpaySignature) {
}
