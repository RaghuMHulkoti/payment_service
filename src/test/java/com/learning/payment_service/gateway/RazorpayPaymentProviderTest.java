package com.learning.payment_service.gateway;

import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;
import com.learning.payment_service.gateway.provider.RazorpayPaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Razorpay adapter: signature check, minimum amount, REST calls and error mapping, against a mock server. */
class RazorpayPaymentProviderTest {

    static final String KEY_ID = "rzp_test_unit";
    static final String SECRET = "unit_test_secret";

    MockRestServiceServer server;
    RazorpayPaymentProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        var builder = RestClient.builder().baseUrl("https://api.razorpay.com/v1")
                .defaultHeaders(h -> h.setBasicAuth(KEY_ID, SECRET));
        server = MockRestServiceServer.bindTo(builder).build();
        var constructor = RazorpayPaymentProvider.class.getDeclaredConstructor(RestClient.class, String.class, String.class);
        constructor.setAccessible(true);
        provider = constructor.newInstance(builder.build(), KEY_ID, SECRET);
    }

    static String sign(String orderId, String paymentId, String secret) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsOnlyTheSignatureOfOrderIdPipePaymentIdUnderTheKeySecret() throws Exception {
        String good = sign("order_1", "pay_1", SECRET);

        assertThat(provider.verifySignature("order_1", "pay_1", good)).isTrue();
        assertThat(provider.verifySignature("order_1", "pay_1", good.toUpperCase())).isTrue();
        assertThat(provider.verifySignature("order_1", "pay_2", good)).isFalse();
        assertThat(provider.verifySignature("order_2", "pay_1", good)).isFalse();
        assertThat(provider.verifySignature("order_1", "pay_1", sign("order_1", "pay_1", "other_secret"))).isFalse();
        assertThat(provider.verifySignature("order_1", "pay_1", "")).isFalse();
        assertThat(provider.verifySignature("order_1", "pay_1", null)).isFalse();
    }

    @Test
    void exposesOnlyThePublicKeyId() {
        assertThat(provider.keyId()).isEqualTo(KEY_ID).doesNotContain(SECRET);
    }

    @Test
    void refusesAmountsBelowOneHundredPaiseWithoutCallingRazorpay() {
        assertThatThrownBy(() -> provider.createIntent(new PaymentProvider.CreateIntentCommand(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("0.99"), "INR", "k"))).isInstanceOfSatisfying(ProviderRejectedException.class,
                e -> assertThat(e.getCode()).isEqualTo("amount_too_small"));
        server.verify();
    }

    @Test
    void createsTheRazorpayOrderInPaiseWithBasicAuth() {
        var intentId = UUID.randomUUID();
        server.expect(requestTo("https://api.razorpay.com/v1/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((KEY_ID + ":" + SECRET).getBytes())))
                .andExpect(jsonPath("$.amount").value(79900)).andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.receipt").value(intentId.toString()))
                .andRespond(withSuccess("{\"id\":\"order_RZP1\",\"status\":\"created\",\"amount\":79900}", MediaType.APPLICATION_JSON));

        var result = provider.createIntent(new PaymentProvider.CreateIntentCommand(intentId, UUID.randomUUID(),
                new BigDecimal("799.00"), "inr", "k"));

        assertThat(result.providerIntentId()).isEqualTo("order_RZP1");
        assertThat(result.clientSecret()).isEqualTo("order_RZP1");
        server.verify();
    }

    @Test
    void badKeysAreAPermanentRefusalAndServerErrorsAreRetryable() {
        server.expect(requestTo("https://api.razorpay.com/v1/orders")).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"description\":\"Authentication failed\"}}"));
        assertThatThrownBy(() -> provider.createIntent(new PaymentProvider.CreateIntentCommand(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "INR", "k"))).isInstanceOfSatisfying(ProviderRejectedException.class,
                e -> assertThat(e.getCode()).isEqualTo("razorpay_authentication"));

        server.reset();
        server.expect(requestTo("https://api.razorpay.com/v1/orders")).andRespond(withServerError());
        assertThatThrownBy(() -> provider.createIntent(new PaymentProvider.CreateIntentCommand(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), "INR", "k"))).isInstanceOf(ProviderTransientException.class);
    }

    @Test
    void serverSideConfirmOnlyAsksForCheckout() {
        var result = provider.confirm(new PaymentProvider.ConfirmCommand("order_1", "anything", "k"));

        assertThat(result.outcome()).isEqualTo(PaymentProvider.ChargeOutcome.REQUIRES_ACTION);
    }

    @Test
    void mapsRazorpayOrderStatus() {
        server.expect(requestTo("https://api.razorpay.com/v1/orders/order_paid"))
                .andRespond(withSuccess("{\"id\":\"order_paid\",\"status\":\"paid\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.razorpay.com/v1/orders/order_open"))
                .andRespond(withSuccess("{\"id\":\"order_open\",\"status\":\"attempted\"}", MediaType.APPLICATION_JSON));

        assertThat(provider.fetchStatus("order_paid")).contains(PaymentProvider.ProviderIntentStatus.SUCCEEDED);
        assertThat(provider.fetchStatus("order_open")).contains(PaymentProvider.ProviderIntentStatus.PENDING);
    }

    @Test
    void refundsTheCapturedPaymentOfTheOrder() {
        server.expect(requestTo("https://api.razorpay.com/v1/orders/order_1/payments"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"pay_failed\",\"status\":\"failed\"},{\"id\":\"pay_ok\",\"status\":\"captured\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.razorpay.com/v1/payments/pay_ok/refund")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(1050))
                .andRespond(withSuccess("{\"id\":\"rfnd_1\",\"status\":\"processed\"}", MediaType.APPLICATION_JSON));

        var result = provider.refund(new PaymentProvider.RefundCommand("order_1", new BigDecimal("10.50"), "INR", "ORDER_ENDED", "refund_1"));

        assertThat(result.outcome()).isEqualTo(PaymentProvider.RefundOutcome.SUCCEEDED);
        assertThat(result.providerRefundId()).isEqualTo("rfnd_1");
        server.verify();
    }
}
