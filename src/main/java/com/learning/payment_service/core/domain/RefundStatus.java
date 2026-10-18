package com.learning.payment_service.core.domain;

public enum RefundStatus {
    INITIATED,
    PROCESSING,
    SUCCESS,
    FAILED;

    /** Counts against the refundable balance (anything not failed). */
    public boolean reservesAmount() {
        return this != FAILED;
    }
}
