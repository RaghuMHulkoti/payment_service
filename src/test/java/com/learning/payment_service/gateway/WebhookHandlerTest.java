package com.learning.payment_service.gateway;

import com.learning.payment_service.core.provider.WebhookHandler.EventKind;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import com.learning.payment_service.exception.InvalidWebhookException;
import com.learning.payment_service.gateway.webhook.RazorpayWebhookHandler;
import com.learning.payment_service.gateway.webhook.StripeWebhookHandler;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookHandlerTest {

    static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final StripeWebhookHandler stripe = new StripeWebhookHandler("whsec", Duration.ofMinutes(5), clock);
    final RazorpayWebhookHandler razorpay = new RazorpayWebhookHandler("rzp");

    @Test
    void stripeAcceptsAValidSignature() {
        assertThat(stripe.verify(stripeRequest("{}", "whsec", NOW.getEpochSecond()))).isTrue();
    }

    @Test
    void stripeRejectsATamperedBodyWrongSecretAndMissingHeader() {
        long t = NOW.getEpochSecond();
        var signed = stripeRequest("{\"a\":1}", "whsec", t);
        var tampered = new WebhookRequest("{\"a\":2}", signed.header());

        assertThat(stripe.verify(tampered)).isFalse();
        assertThat(stripe.verify(stripeRequest("{}", "other-secret", t))).isFalse();
        assertThat(stripe.verify(new WebhookRequest("{}", h -> null))).isFalse();
    }

    @Test
    void stripeRejectsAReplayedOldDelivery() {
        long old = NOW.minus(Duration.ofMinutes(6)).getEpochSecond();

        assertThat(stripe.verify(stripeRequest("{}", "whsec", old))).isFalse();
    }

    @Test
    void stripeAcceptsAnyMatchingV1WhenSeveralAreSent() {
        long t = NOW.getEpochSecond();
        String good = hmac("whsec", t + ".{}");
        var request = new WebhookRequest("{}", h -> "t=" + t + ",v1=bad,v1=" + good);

        assertThat(stripe.verify(request)).isTrue();
    }

    @Test
    void handlersFailClosedWhenNoSecretIsConfigured() {
        var noSecretStripe = new StripeWebhookHandler("", Duration.ofMinutes(5), clock);
        var noSecretRazorpay = new RazorpayWebhookHandler("");

        assertThat(noSecretStripe.verify(stripeRequest("{}", "", NOW.getEpochSecond()))).isFalse();
        assertThat(noSecretRazorpay.verify(razorpayRequest("{}", ""))).isFalse();
    }

    @Test
    void razorpayVerifiesTheBodySignature() {
        assertThat(razorpay.verify(razorpayRequest("{\"event\":\"x\"}", "rzp"))).isTrue();
        assertThat(razorpay.verify(razorpayRequest("{\"event\":\"x\"}", "wrong"))).isFalse();
    }

    @Test
    void razorpayNeedsAnEventIdHeader() {
        assertThatThrownBy(() -> razorpay.eventId(new WebhookRequest("{}", h -> null)))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    void stripeNormalisesEventTypes() {
        var ok = stripe.normalize("{\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\"}}}");
        assertThat(ok.kind()).isEqualTo(EventKind.PAYMENT_SUCCEEDED);
        assertThat(ok.providerIntentId()).isEqualTo("pi_1");

        var failed = stripe.normalize("{\"type\":\"payment_intent.payment_failed\",\"data\":{\"object\":{\"id\":\"pi_1\","
                + "\"last_payment_error\":{\"code\":\"card_declined\",\"message\":\"no\"}}}}");
        assertThat(failed.kind()).isEqualTo(EventKind.PAYMENT_FAILED);
        assertThat(failed.failureCode()).isEqualTo("card_declined");

        var refund = stripe.normalize("{\"type\":\"refund.updated\",\"data\":{\"object\":{\"id\":\"re_1\","
                + "\"status\":\"succeeded\",\"payment_intent\":\"pi_1\"}}}");
        assertThat(refund.kind()).isEqualTo(EventKind.REFUND_SUCCEEDED);
        assertThat(refund.providerRefundId()).isEqualTo("re_1");

        assertThat(stripe.normalize("{\"type\":\"customer.created\",\"data\":{\"object\":{}}}").kind())
                .isEqualTo(EventKind.IGNORED);
    }

    @Test
    void razorpayNormalisesEventTypes() {
        var captured = razorpay.normalize("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"order_id\":\"order_1\"}}}}");
        assertThat(captured.kind()).isEqualTo(EventKind.PAYMENT_SUCCEEDED);
        assertThat(captured.providerIntentId()).isEqualTo("order_1");

        var refund = razorpay.normalize("{\"event\":\"refund.failed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\"}}}}");
        assertThat(refund.kind()).isEqualTo(EventKind.REFUND_FAILED);
        assertThat(refund.providerRefundId()).isEqualTo("rfnd_1");
    }

    @Test
    void malformedBodiesAreRejectedAs400NotAs500() {
        assertThatThrownBy(() -> stripe.normalize("not json"))
                .isInstanceOfSatisfying(InvalidWebhookException.class, e -> assertThat(e.isBadSignature()).isFalse());
        assertThatThrownBy(() -> razorpay.eventType("[1,2]")).isInstanceOf(InvalidWebhookException.class);
    }

    static WebhookRequest stripeRequest(String body, String secret, long timestamp) {
        String header = "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
        return new WebhookRequest(body, h -> "Stripe-Signature".equalsIgnoreCase(h) ? header : null);
    }

    static WebhookRequest razorpayRequest(String body, String secret) {
        String signature = hmac(secret, body);
        return new WebhookRequest(body, h -> "X-Razorpay-Signature".equalsIgnoreCase(h) ? signature : null);
    }

    static String hmac(String secret, String message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.isEmpty() ? new byte[]{0} : secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
