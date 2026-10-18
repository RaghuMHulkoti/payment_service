package com.learning.payment_service.exception;

import java.util.UUID;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID id) {
        super("Payment " + id + " not found");
    }

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
