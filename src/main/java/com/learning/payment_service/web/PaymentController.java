package com.learning.payment_service.web;

import com.learning.payment_service.common.StatusCode;
import com.learning.payment_service.core.processor.ConfirmIntentProcessor;
import com.learning.payment_service.core.processor.CreateIntentProcessor;
import com.learning.payment_service.core.processor.CreateRefundProcessor;
import com.learning.payment_service.core.processor.GetPaymentProcessor;
import com.learning.payment_service.web.request.ConfirmIntentRequest;
import com.learning.payment_service.web.request.CreateIntentRequest;
import com.learning.payment_service.web.request.CreateRefundRequest;
import com.learning.payment_service.web.response.PaymentResponse;
import com.learning.payment_service.web.response.RefundResponse;
import com.learning.payment_service.web.response.Response;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Payment API (TDD §11.7). The {@code /internal} routes are called service-to-service (Order, Returns) behind
 * mTLS at the gateway; the status route identifies the caller by {@code X-User-Id}, set by the gateway after it
 * validates the JWT. This service is never exposed without the gateway in front of it.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payments")
public class PaymentController {

    private static final String USER_HEADER = "X-User-Id";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final CreateIntentProcessor createIntentProcessor;
    private final ConfirmIntentProcessor confirmIntentProcessor;
    private final CreateRefundProcessor createRefundProcessor;
    private final GetPaymentProcessor getPaymentProcessor;

    public PaymentController(CreateIntentProcessor createIntentProcessor, ConfirmIntentProcessor confirmIntentProcessor,
                             CreateRefundProcessor createRefundProcessor, GetPaymentProcessor getPaymentProcessor) {
        this.createIntentProcessor = createIntentProcessor;
        this.confirmIntentProcessor = confirmIntentProcessor;
        this.createRefundProcessor = createRefundProcessor;
        this.getPaymentProcessor = getPaymentProcessor;
    }

    @PostMapping("/internal/payments/intents")
    @Operation(summary = "Create a payment intent for an order (idempotent). 201 when new, 200 on a replay of the same key")
    public ResponseEntity<Response<PaymentResponse>> createIntent(
            @Parameter(description = "Unique per payment request, e.g. order_<orderId>")
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateIntentRequest request) {
        var result = createIntentProcessor.process(new CreateIntentProcessor.Request(idempotencyKey,
                request.orderId(), request.customerId(), request.amount(), request.currency(), request.provider()));
        var body = PaymentResponseMapper.toResponse(result.intent(), result.clientSecret(), List.of());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new Response<>(StatusCode.SUCCESS,
                        result.created() ? "Payment intent created" : "Payment intent already exists", body));
    }

    @PostMapping("/internal/payments/intents/{id}/confirm")
    @Operation(summary = "Server-side confirm of an intent with a payment method token (optional path)")
    public ResponseEntity<Response<PaymentResponse>> confirm(@PathVariable UUID id,
                                                             @Valid @RequestBody ConfirmIntentRequest request) {
        var result = confirmIntentProcessor.process(new ConfirmIntentProcessor.Request(id, request.paymentMethodToken()));
        return ok("Payment " + result.intent().status().name().toLowerCase().replace('_', ' '),
                PaymentResponseMapper.toResponse(result.intent(), null, List.of()));
    }

    @PostMapping("/internal/payments/refunds")
    @Operation(summary = "Refund a captured payment, in full or in part (idempotent, key e.g. refund_<returnId>)")
    public ResponseEntity<Response<RefundResponse>> refund(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {
        var result = createRefundProcessor.process(new CreateRefundProcessor.Request(idempotencyKey,
                request.orderId(), request.amount(), request.reason()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new Response<>(StatusCode.SUCCESS,
                        result.created() ? "Refund " + result.refund().status().name().toLowerCase()
                                : "Refund already exists",
                        PaymentResponseMapper.toResponse(result.refund())));
    }

    @GetMapping("/payments/{id}")
    @Operation(summary = "Payment status; only the owning customer can read it")
    public ResponseEntity<Response<PaymentResponse>> get(
            @Parameter(hidden = true) @RequestHeader(USER_HEADER) UUID userId, @PathVariable UUID id) {
        var result = getPaymentProcessor.process(new GetPaymentProcessor.Request(id, userId));
        return ok("Payment retrieved successfully",
                PaymentResponseMapper.toResponse(result.intent(), null, result.refunds()));
    }

    private static <T> ResponseEntity<Response<T>> ok(String message, T body) {
        return ResponseEntity.ok(new Response<>(StatusCode.SUCCESS, message, body));
    }
}
