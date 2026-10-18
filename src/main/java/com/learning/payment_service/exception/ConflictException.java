package com.learning.payment_service.exception;

/** A concurrent request changed the same row first (optimistic lock). The caller should retry. Maps to 409. */
public class ConflictException extends RuntimeException {

    private final String errorCode;

    public ConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
