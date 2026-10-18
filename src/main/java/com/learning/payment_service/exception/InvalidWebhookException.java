package com.learning.payment_service.exception;

/** A webhook failed signature verification (401) or could not be parsed (400). */
public class InvalidWebhookException extends RuntimeException {

    private final boolean badSignature;

    private InvalidWebhookException(String message, boolean badSignature) {
        super(message);
        this.badSignature = badSignature;
    }

    public static InvalidWebhookException badSignature(String message) {
        return new InvalidWebhookException(message, true);
    }

    public static InvalidWebhookException malformed(String message) {
        return new InvalidWebhookException(message, false);
    }

    public boolean isBadSignature() {
        return badSignature;
    }
}
