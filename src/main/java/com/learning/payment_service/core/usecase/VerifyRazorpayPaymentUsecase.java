package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.PaymentAttempt;
import com.learning.payment_service.core.domain.PaymentIntent;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.RazorpayCheckout;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentService;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentServiceRequest;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.exception.InvalidSignatureException;
import com.learning.payment_service.exception.PaymentNotFoundException;
import com.learning.payment_service.gateway.PaymentGateway;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Razorpay Standard Checkout result check. The three values the browser got from Checkout are proof of payment only
 * if the signature matches HMAC-SHA256(order_id|payment_id) under the key secret. A mismatch changes nothing: the
 * payment is not marked paid and not failed either, so a forged request cannot hurt a real one. A match captures the
 * payment and emits PAYMENT_SUCCESS, which settles the order. Repeating a verified call is a no-op.
 */
public class VerifyRazorpayPaymentUsecase
        implements Usecase<VerifyRazorpayPaymentUsecase.Request, VerifyRazorpayPaymentUsecase.Response> {

    public record Request(UUID paymentId, UUID customerId, String razorpayOrderId, String razorpayPaymentId,
                          String razorpaySignature) implements UsecaseRequest {
    }

    public record Response(PaymentIntent intent) implements UsecaseResponse {
    }

    private final PaymentGateway gateway;
    private final RazorpayCheckout checkout;
    private final CaptureIntentService capture;
    private final Clock clock;

    /** {@code checkout} is null while Razorpay keys are not configured. */
    public VerifyRazorpayPaymentUsecase(PaymentGateway gateway, RazorpayCheckout checkout, CaptureIntentService capture,
                                        Clock clock) {
        this.gateway = gateway;
        this.checkout = checkout;
        this.capture = capture;
        this.clock = clock;
    }

    @Override
    public Response execute(Request request) {
        var intent = gateway.findIntent(request.paymentId())
                .filter(i -> i.customerId().equals(request.customerId()))
                .orElseThrow(() -> new PaymentNotFoundException(request.paymentId()));
        if (intent.provider() != Provider.RAZORPAY) {
            throw new BusinessRuleViolationException("NOT_A_RAZORPAY_PAYMENT", "This payment is not paid through Razorpay");
        }
        if (checkout == null) {
            throw new DependencyUnavailableException("Razorpay is not configured on this service", null);
        }
        if (!request.razorpayOrderId().equals(intent.providerIntentId())
                || !checkout.verifySignature(request.razorpayOrderId(), request.razorpayPaymentId(), request.razorpaySignature())) {
            throw new InvalidSignatureException("Payment signature does not match");
        }
        if (intent.status().wasCaptured()) {
            return new Response(intent);
        }
        if (!intent.status().isOpen()) {
            throw new BusinessRuleViolationException("INVALID_PAYMENT_STATE",
                    "Payment is " + intent.status() + " and cannot be completed");
        }
        int attemptNo = gateway.nextAttemptNo(intent.id());
        var captured = gateway.inTransaction(() -> {
            gateway.insertAttempt(new PaymentAttempt(UUID.randomUUID(), intent.id(), attemptNo, request.razorpayPaymentId(),
                    "SUCCEEDED", "{\"verified\":\"checkout_signature\"}",
                    OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)));
            return capture.execute(CaptureIntentServiceRequest.builder().intent(intent).build()).getIntent();
        });
        return new Response(captured);
    }
}
