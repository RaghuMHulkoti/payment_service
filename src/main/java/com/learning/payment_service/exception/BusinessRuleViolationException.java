package com.learning.payment_service.exception;

/** A well-formed request that breaks a payment rule (wrong state, over-refund, reused key). Maps to 422. */
public class BusinessRuleViolationException extends RuntimeException {

    private final String errorCode;

    public BusinessRuleViolationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
