package com.learning.payment_service.gateway.messaging;

import com.learning.payment_service.core.usecase.HandlePaymentRequestedUsecase;
import org.springframework.kafka.annotation.KafkaListener;

/** Feeds Order Service's events into payment creation. Failures propagate so the error handler retries, then dead-letters. */
public class OrderEventListener {

    private final HandlePaymentRequestedUsecase usecase;

    public OrderEventListener(HandlePaymentRequestedUsecase usecase) {
        this.usecase = usecase;
    }

    @KafkaListener(topics = "${payment.events.order-topic:domain.order.events}", groupId = "${spring.kafka.consumer.group-id:payment-service}")
    public void onOrderEvent(String payload) {
        usecase.execute(new HandlePaymentRequestedUsecase.Request(payload));
    }
}
