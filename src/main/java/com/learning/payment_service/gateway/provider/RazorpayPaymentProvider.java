package com.learning.payment_service.gateway.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;
import com.learning.payment_service.core.provider.RazorpayCheckout;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Razorpay Standard Checkout over Razorpay's REST API (basic auth with the key id and secret). {@link #createIntent}
 * creates the Razorpay order ({@code POST /v1/orders}: amount in paise, currency, receipt = our intent id); the
 * browser opens Checkout with that order id and the public key id; the result is proven by {@link #verifySignature}
 * before anything is marked paid. A server cannot confirm a Razorpay payment itself, so {@link #confirm} only reports
 * that the customer must finish in Checkout. Test keys ({@code rzp_test_...}) run in Razorpay's free test mode.
 */
public class RazorpayPaymentProvider implements PaymentProvider, RazorpayCheckout {

    static final long MIN_PAISE = 100;
    private static final String BASE_URL = "https://api.razorpay.com/v1";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RzpOrder(String id, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RzpPayment(String id, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RzpPayments(List<RzpPayment> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RzpRefund(String id, String status) {
    }

    private final RestClient http;
    private final String keyId;
    private final String keySecret;

    public RazorpayPaymentProvider(String keyId, String keySecret) {
        this(defaultClient(keyId, keySecret), keyId, keySecret);
    }

    RazorpayPaymentProvider(RestClient http, String keyId, String keySecret) {
        this.http = http;
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    private static RestClient defaultClient(String keyId, String keySecret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().baseUrl(BASE_URL).requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(keyId, keySecret)).build();
    }

    @Override
    public Provider id() {
        return Provider.RAZORPAY;
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public ProviderIntent createIntent(CreateIntentCommand command) {
        long paise = paise(command.amount());
        if (paise < MIN_PAISE) {
            throw new ProviderRejectedException("amount_too_small", "Razorpay needs at least 100 paise (1.00)");
        }
        var body = Map.of("amount", paise, "currency", command.currency().toUpperCase(),
                "receipt", command.intentId().toString(), "notes", Map.of("orderId", command.orderId().toString()));
        var created = call(() -> http.post().uri("/orders").body(body).retrieve().body(RzpOrder.class));
        return new ProviderIntent(created.id(), created.id());
    }

    /** Checkout takes the Razorpay order id plus the key id, so the order id is the only "secret" returned. */
    @Override
    public String clientSecret(String providerIntentId) {
        return providerIntentId;
    }

    @Override
    public ChargeResult confirm(ConfirmCommand command) {
        return new ChargeResult(ChargeOutcome.REQUIRES_ACTION, null, null, null, "{\"status\":\"complete_in_checkout\"}");
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        var payments = call(() -> http.get().uri("/orders/{id}/payments", command.providerIntentId()).retrieve()
                .body(RzpPayments.class));
        var captured = payments.items() == null ? null : payments.items().stream()
                .filter(p -> "captured".equals(p.status())).findFirst().orElse(null);
        if (captured == null) {
            throw new ProviderRejectedException("no_captured_payment", "Razorpay has no captured payment for this order");
        }
        var body = Map.of("amount", paise(command.amount()), "receipt", command.idempotencyKey(),
                "notes", Map.of("reason", String.valueOf(command.reason())));
        var refund = call(() -> http.post().uri("/payments/{id}/refund", captured.id()).body(body).retrieve()
                .body(RzpRefund.class));
        return switch (String.valueOf(refund.status())) {
            case "processed" -> new RefundResult(RefundOutcome.SUCCEEDED, refund.id(), null);
            case "failed" -> new RefundResult(RefundOutcome.FAILED, refund.id(), "Razorpay refund failed");
            default -> new RefundResult(RefundOutcome.PROCESSING, refund.id(), null);
        };
    }

    /** Razorpay orders cannot be cancelled; an unpaid one simply expires, and our own intent is closed separately. */
    @Override
    public void cancel(String providerIntentId, String idempotencyKey) {
        // nothing to call
    }

    @Override
    public Optional<ProviderIntentStatus> fetchStatus(String providerIntentId) {
        try {
            var order = http.get().uri("/orders/{id}", providerIntentId).retrieve().body(RzpOrder.class);
            return Optional.of("paid".equals(order.status()) ? ProviderIntentStatus.SUCCEEDED : ProviderIntentStatus.PENDING);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 404) {
                return Optional.empty(); // Razorpay does not know the id
            }
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw translate(e);
        }
    }

    @Override
    public boolean verifySignature(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null || signature == null) {
            return false;
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var expected = HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static long paise(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw translate(e);
        }
    }

    /** Bad keys and bad requests are permanent; network trouble and 5xx are retried by the caller. */
    private static RuntimeException translate(Exception e) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 401) {
                return new ProviderRejectedException("razorpay_authentication", "Razorpay rejected the API keys");
            }
            if (status >= 400 && status < 500 && status != 429) {
                return new ProviderRejectedException("razorpay_bad_request", "Razorpay refused the request (" + status + ")");
            }
        }
        return new ProviderTransientException("Razorpay unavailable: " + e.getMessage(), e);
    }
}
