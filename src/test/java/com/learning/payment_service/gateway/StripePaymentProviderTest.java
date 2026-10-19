package com.learning.payment_service.gateway;

import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRejectedException;
import com.learning.payment_service.core.provider.ProviderTransientException;
import com.learning.payment_service.gateway.provider.StripePaymentProvider;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Stripe adapter against a mocked SDK client: mapping of outcomes and errors, units and idempotency keys. */
class StripePaymentProviderTest {

    StripeClient client;
    StripePaymentProvider provider;

    @BeforeEach
    void setUp() {
        client = mock(StripeClient.class, Answers.RETURNS_DEEP_STUBS);
        provider = new StripePaymentProvider(client);
    }

    @Test
    void createIntentSendsMinorUnitsLowercaseCurrencyAndTheIdempotencyKey() throws Exception {
        var created = new PaymentIntent();
        created.setId("pi_123");
        created.setClientSecret("pi_123_secret_abc");
        when(client.paymentIntents().create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(created);

        var result = provider.createIntent(new PaymentProvider.CreateIntentCommand(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("799.00"), "INR", "order_1"));

        assertThat(result.providerIntentId()).isEqualTo("pi_123");
        assertThat(result.clientSecret()).isEqualTo("pi_123_secret_abc");
        var params = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
        var options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(client.paymentIntents()).create(params.capture(), options.capture());
        assertThat(params.getValue().getAmount()).isEqualTo(79900L);
        assertThat(params.getValue().getCurrency()).isEqualTo("inr");
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("order_1");
    }

    @Test
    void successfulConfirmIsSucceededWithTheChargeId() throws Exception {
        var confirmed = new PaymentIntent();
        confirmed.setStatus("succeeded");
        confirmed.setLatestCharge("ch_1");
        when(client.paymentIntents().confirm(eq("pi_1"), any(PaymentIntentConfirmParams.class), any(RequestOptions.class)))
                .thenReturn(confirmed);

        var result = provider.confirm(new PaymentProvider.ConfirmCommand("pi_1", "pm_card_visa", "k:1"));

        assertThat(result.outcome()).isEqualTo(PaymentProvider.ChargeOutcome.SUCCEEDED);
        assertThat(result.providerChargeId()).isEqualTo("ch_1");
    }

    @Test
    void threeDSecureIsRequiresAction() throws Exception {
        var confirmed = new PaymentIntent();
        confirmed.setStatus("requires_action");
        when(client.paymentIntents().confirm(eq("pi_1"), any(PaymentIntentConfirmParams.class), any(RequestOptions.class)))
                .thenReturn(confirmed);

        assertThat(provider.confirm(new PaymentProvider.ConfirmCommand("pi_1", "pm_card_threeDSecure2Required", "k:1")).outcome())
                .isEqualTo(PaymentProvider.ChargeOutcome.REQUIRES_ACTION);
    }

    @Test
    void aCardErrorIsADeclineWithTheDeclineCode() throws Exception {
        when(client.paymentIntents().confirm(eq("pi_1"), any(PaymentIntentConfirmParams.class), any(RequestOptions.class)))
                .thenThrow(new CardException("Your card was declined.", "req_1", "card_declined", "param", "generic_decline", null, 402, null));

        var result = provider.confirm(new PaymentProvider.ConfirmCommand("pi_1", "pm_card_chargeDeclined", "k:1"));

        assertThat(result.outcome()).isEqualTo(PaymentProvider.ChargeOutcome.DECLINED);
        assertThat(result.failureCode()).isEqualTo("generic_decline");
    }

    @Test
    void networkFailureIsTransientAndABadRequestIsRejected() throws Exception {
        when(client.paymentIntents().confirm(eq("pi_1"), any(PaymentIntentConfirmParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("timeout"));
        assertThatThrownBy(() -> provider.confirm(new PaymentProvider.ConfirmCommand("pi_1", "pm_card_visa", "k:1")))
                .isInstanceOf(ProviderTransientException.class);

        when(client.paymentIntents().confirm(eq("pi_2"), any(PaymentIntentConfirmParams.class), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException("No such payment_method", "payment_method", "req_2", "resource_missing", 400, null));
        assertThatThrownBy(() -> provider.confirm(new PaymentProvider.ConfirmCommand("pi_2", "pm_bad", "k:1")))
                .isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void refundIsSentInMinorUnitsWithTheKey() throws Exception {
        var refund = new Refund();
        refund.setId("re_1");
        refund.setStatus("succeeded");
        when(client.refunds().create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(refund);

        var result = provider.refund(new PaymentProvider.RefundCommand("pi_1", new BigDecimal("10.50"), "USD", "ORDER_ENDED", "refund_1"));

        assertThat(result.outcome()).isEqualTo(PaymentProvider.RefundOutcome.SUCCEEDED);
        var params = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(client.refunds()).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getAmount()).isEqualTo(1050L);
    }
}
