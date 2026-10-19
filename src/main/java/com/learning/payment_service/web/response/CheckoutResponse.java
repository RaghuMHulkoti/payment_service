package com.learning.payment_service.web.response;

import java.util.UUID;

/** Everything Checkout needs. {@code keyId} is the public key; the key secret is never sent to a browser. */
public record CheckoutResponse(UUID paymentId, String keyId, String orderId, long amount, String currency) {
}
