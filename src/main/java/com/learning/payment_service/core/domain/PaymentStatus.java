package com.learning.payment_service.core.domain;

public enum PaymentStatus {
    INITIATED,
    REQUIRES_ACTION,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    /** Still waiting for the customer or the provider; can move to CAPTURED, FAILED or CANCELLED. */
    public boolean isOpen() {
        return this == INITIATED || this == REQUIRES_ACTION || this == AUTHORIZED;
    }

    /** Money was captured and has not been fully returned. */
    public boolean isRefundable() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED;
    }

    /** Money was captured at some point: an order with such an intent is already paid for. */
    public boolean wasCaptured() {
        return this == CAPTURED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
