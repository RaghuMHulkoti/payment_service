package com.learning.payment_service.web;

import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.processor.ConfirmIntentProcessor;
import com.learning.payment_service.core.processor.CreateIntentProcessor;
import com.learning.payment_service.core.processor.CreateIntentProcessorResponse;
import com.learning.payment_service.core.processor.CreateRefundProcessor;
import com.learning.payment_service.core.processor.GetCheckoutProcessor;
import com.learning.payment_service.core.processor.GetPaymentProcessor;
import com.learning.payment_service.core.processor.VerifyRazorpayPaymentProcessor;
import com.learning.payment_service.exception.InvalidSignatureException;
import com.learning.payment_service.core.processor.ReceiveWebhookProcessor;
import com.learning.payment_service.core.processor.ReceiveWebhookProcessorResponse;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import com.learning.payment_service.exception.GlobalExceptionHandler;
import com.learning.payment_service.exception.InvalidWebhookException;
import com.learning.payment_service.exception.PaymentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {

    final UUID order = UUID.randomUUID();
    final UUID customer = UUID.randomUUID();
    CreateIntentProcessor create = Mockito.mock(CreateIntentProcessor.class);
    GetPaymentProcessor getPayment = Mockito.mock(GetPaymentProcessor.class);
    ReceiveWebhookProcessor webhook = Mockito.mock(ReceiveWebhookProcessor.class);
    GetCheckoutProcessor checkout = Mockito.mock(GetCheckoutProcessor.class);
    VerifyRazorpayPaymentProcessor verify = Mockito.mock(VerifyRazorpayPaymentProcessor.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var payments = new PaymentController(create, Mockito.mock(ConfirmIntentProcessor.class),
                Mockito.mock(CreateRefundProcessor.class), getPayment, checkout, verify);
        mvc = MockMvcBuilders.standaloneSetup(payments, new WebhookController(webhook))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void verifyWithMissingFieldsIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/payments/" + UUID.randomUUID() + "/razorpay/verify").header("X-User-Id", customer.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"razorpay_order_id\":\"order_1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void verifyWithAWrongSignatureIs400AndNothingIsPaid() throws Exception {
        when(verify.process(any())).thenThrow(new InvalidSignatureException("Payment signature does not match"));

        mvc.perform(post("/api/v1/payments/" + UUID.randomUUID() + "/razorpay/verify").header("X-User-Id", customer.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"razorpay_order_id\":\"order_1\",\"razorpay_payment_id\":\"pay_1\",\"razorpay_signature\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.errorCode").value("INVALID_SIGNATURE"));
    }

    @Test
    void checkoutAndVerifyNeedTheCallerIdentity() throws Exception {
        mvc.perform(get("/api/v1/payments/" + UUID.randomUUID() + "/checkout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/" + UUID.randomUUID() + "/razorpay/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void createRequiresTheIdempotencyKeyHeader() throws Exception {
        mvc.perform(post("/api/v1/internal/payments/intents").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.errorCode").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void createValidatesTheBody() throws Exception {
        mvc.perform(post("/api/v1/internal/payments/intents").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + order + "\",\"customerId\":\"" + customer
                                + "\",\"amount\":-5,\"currency\":\"inr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void createReturns201WithTheClientSecretOnFirstCallAnd200OnReplay() throws Exception {
        var intent = intent();
        when(create.process(any())).thenReturn(new CreateIntentProcessorResponse(intent, "secret_1", true));
        mvc.perform(post("/api/v1/internal/payments/intents").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.body.clientSecret").value("secret_1"))
                .andExpect(jsonPath("$.body.paymentId").value(intent.id().toString()));

        when(create.process(any())).thenReturn(new CreateIntentProcessorResponse(intent, "secret_1", false));
        mvc.perform(post("/api/v1/internal/payments/intents").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk());
    }

    @Test
    void businessRuleViolationIs422WithItsCode() throws Exception {
        when(create.process(any())).thenThrow(new BusinessRuleViolationException("ORDER_ALREADY_PAID", "paid"));

        mvc.perform(post("/api/v1/internal/payments/intents").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.body.errorCode").value("ORDER_ALREADY_PAID"));
    }

    @Test
    void statusWithoutUserHeaderIs401AndUnknownPaymentIs404() throws Exception {
        mvc.perform(get("/api/v1/payments/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.body.errorCode").value("UNAUTHORIZED"));

        when(getPayment.process(any())).thenThrow(new PaymentNotFoundException(UUID.randomUUID()));
        mvc.perform(get("/api/v1/payments/" + UUID.randomUUID()).header("X-User-Id", customer.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.body.errorCode").value("NOT_FOUND"));
    }

    @Test
    void webhookWithABadSignatureIs401AndAValidOneIs200() throws Exception {
        when(webhook.process(any())).thenThrow(InvalidWebhookException.badSignature("Invalid webhook signature"));
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.body.errorCode").value("INVALID_SIGNATURE"));

        Mockito.reset(webhook);
        when(webhook.process(any())).thenReturn(new ReceiveWebhookProcessorResponse(false));
        mvc.perform(post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    String body() {
        return "{\"orderId\":\"" + order + "\",\"customerId\":\"" + customer + "\",\"amount\":1299.00,\"currency\":\"INR\"}";
    }

    PaymentIntent intent() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return PaymentIntent.initiated(order, customer, new BigDecimal("1299.00"), "INR", Provider.RAZORPAY, "k", now)
                .withProviderIntent("order_sim_1", com.learning.payment_service.core.domain.PaymentStatus.REQUIRES_ACTION, now);
    }
}
