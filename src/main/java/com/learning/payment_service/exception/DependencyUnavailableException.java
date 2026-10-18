package com.learning.payment_service.exception;

/** The payment database or the payment provider did not answer. Writes refuse rather than guess. Maps to 503. */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
