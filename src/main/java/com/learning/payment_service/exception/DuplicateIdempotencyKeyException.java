package com.learning.payment_service.exception;

/** Gateway signal: a row with this idempotency key already exists (lost a race). The use case re-reads it. */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(String key) {
        super("Idempotency key already used: " + key);
    }
}
