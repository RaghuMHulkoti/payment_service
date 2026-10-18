package com.learning.payment_service.core.provider;

/** Permanent PSP refusal of a request (invalid amount, unknown account). Not retried. */
public class ProviderRejectedException extends RuntimeException {

    private final String code;

    public ProviderRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
