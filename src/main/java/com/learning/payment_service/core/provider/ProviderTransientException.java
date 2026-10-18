package com.learning.payment_service.core.provider;

/** Retryable PSP failure: 5xx, timeout, network. */
public class ProviderTransientException extends RuntimeException {

    public ProviderTransientException(String message) {
        super(message);
    }

    public ProviderTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
