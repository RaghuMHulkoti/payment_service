package com.learning.payment_service.exception;

/** A Checkout result whose signature does not match: not proof of payment, so nothing is marked paid. Maps to 400. */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}
