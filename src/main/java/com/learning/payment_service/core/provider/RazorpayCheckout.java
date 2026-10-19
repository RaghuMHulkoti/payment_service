package com.learning.payment_service.core.provider;

/** What Razorpay Standard Checkout needs from the server besides the order itself. */
public interface RazorpayCheckout {

    /** The public key id the browser passes to Checkout. Never the secret. */
    String keyId();

    /** True only when {@code signature} is HMAC-SHA256 of {@code orderId|paymentId} under the key secret. */
    boolean verifySignature(String orderId, String paymentId, String signature);
}
