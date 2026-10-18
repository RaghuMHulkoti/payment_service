package com.learning.payment_service.core.service;

import com.learning.payment_service.core.domain.PaymentIntent;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/** Helpers shared by the state-transition services. */
public final class ServiceSupport {

    private ServiceSupport() {
    }

    public static OffsetDateTime now(Clock clock) {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Event payload fields common to every intent transition. */
    public static Map<String, Object> intentEventData(PaymentIntent intent) {
        var data = new LinkedHashMap<String, Object>();
        data.put("orderId", intent.orderId().toString());
        data.put("paymentIntentId", intent.id().toString());
        data.put("amount", intent.amount());
        data.put("currency", intent.currency());
        data.put("provider", intent.provider().name());
        return data;
    }
}
