package com.learning.payment_service.gateway.messaging;

import com.learning.payment_service.core.usecase.HandleOrderEndedUsecase;
import com.learning.payment_service.core.usecase.HandlePaymentRequestedUsecase;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Feeds Order Service's events into payments: PAYMENT_REQUESTED opens an intent, ORDER_CANCELLED / ORDER_FAILED close
 * the open ones. Each handler ignores the other's events. Failures propagate so the error handler retries, then dead-letters.
 */
public class OrderEventListener {

    private final HandlePaymentRequestedUsecase requested;
    private final HandleOrderEndedUsecase ended;

    public OrderEventListener(HandlePaymentRequestedUsecase requested, HandleOrderEndedUsecase ended) {
        this.requested = requested;
        this.ended = ended;
    }

    @KafkaListener(topics = "${payment.events.order-topic:domain.order.events}", groupId = "${spring.kafka.consumer.group-id:payment-service}")
    public void onOrderEvent(String payload) {
        requested.execute(new HandlePaymentRequestedUsecase.Request(payload));
        ended.execute(new HandleOrderEndedUsecase.Request(payload));
    }
}
