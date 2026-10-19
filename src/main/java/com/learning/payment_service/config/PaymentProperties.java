package com.learning.payment_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        @DefaultValue("3") int providerMaxAttempts,
        @DefaultValue("200ms") Duration providerBackoff,
        @DefaultValue("1h") Duration stuckAfter,
        @DefaultValue Jobs jobs,
        @DefaultValue Stripe stripe,
        @DefaultValue Razorpay razorpay) {

    public record Jobs(@DefaultValue("true") boolean enabled,
                       @DefaultValue("5s") Duration webhookPollInterval,
                       @DefaultValue("15m") Duration reconcileInterval) {
    }

    /** {@code apiKey}: the Stripe secret key (a test key {@code sk_test_...} in test mode); empty keeps Stripe simulated. */
    public record Stripe(@DefaultValue("") String webhookSecret, @DefaultValue("5m") Duration webhookTolerance,
                         @DefaultValue("") String apiKey) {
    }

    public record Razorpay(@DefaultValue("") String webhookSecret) {
    }
}
