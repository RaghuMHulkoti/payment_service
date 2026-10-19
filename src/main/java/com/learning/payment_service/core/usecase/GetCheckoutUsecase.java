package com.learning.payment_service.core.usecase;

import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.RazorpayCheckout;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.exception.PaymentNotFoundException;
import com.learning.payment_service.gateway.PaymentGateway;

import java.math.RoundingMode;
import java.util.UUID;

/**
 * What the browser needs to open Razorpay Standard Checkout for a payment: the public key id, the Razorpay order id,
 * and the amount in paise. Only the paying customer can ask, and only while the payment is still open.
 */
public class GetCheckoutUsecase implements Usecase<GetCheckoutUsecase.Request, GetCheckoutUsecase.Response> {

    public record Request(UUID paymentId, UUID customerId) implements UsecaseRequest {
    }

    public record Response(UUID paymentId, String keyId, String razorpayOrderId, long amountPaise, String currency)
            implements UsecaseResponse {
    }

    private final PaymentGateway gateway;
    private final RazorpayCheckout checkout;

    /** {@code checkout} is null while Razorpay keys are not configured. */
    public GetCheckoutUsecase(PaymentGateway gateway, RazorpayCheckout checkout) {
        this.gateway = gateway;
        this.checkout = checkout;
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
        if (!intent.status().isOpen() || intent.providerIntentId() == null) {
            throw new BusinessRuleViolationException("CHECKOUT_NOT_AVAILABLE",
                    "Payment is " + intent.status() + " and cannot be paid in Checkout");
        }
        long paise = intent.amount().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new Response(intent.id(), checkout.keyId(), intent.providerIntentId(), paise, intent.currency());
    }
}
