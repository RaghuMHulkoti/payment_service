package com.learning.payment_service.core;

import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.domain.RefundStatus;
import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.provider.RetryingProviderCall;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import com.learning.payment_service.core.provider.WebhookHandlers;
import com.learning.payment_service.core.service.intent.cancel.CancelIntentService;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentService;
import com.learning.payment_service.core.service.intent.fail.FailIntentService;
import com.learning.payment_service.core.service.intent.requiresaction.MarkIntentRequiresActionService;
import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.refund.complete.CompleteRefundService;
import com.learning.payment_service.core.service.refund.fail.FailRefundService;
import com.learning.payment_service.core.usecase.*;
import com.learning.payment_service.core.usecase.ConfirmIntentUsecase;
import com.learning.payment_service.core.usecase.CreateIntentUsecase;
import com.learning.payment_service.core.usecase.CreateRefundUsecase;
import com.learning.payment_service.core.usecase.GetPaymentUsecase;
import com.learning.payment_service.core.usecase.HandlePaymentRequestedUsecase;
import com.learning.payment_service.core.usecase.ProcessWebhookEventsUsecase;
import com.learning.payment_service.core.usecase.ReceiveWebhookUsecase;
import com.learning.payment_service.core.usecase.ReconcileStuckIntentsUsecase;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.exception.InvalidWebhookException;
import com.learning.payment_service.exception.PaymentNotFoundException;
import com.learning.payment_service.gateway.PaymentGateway;
import com.learning.payment_service.gateway.WebhookEventGateway;
import com.learning.payment_service.gateway.provider.SimulatedPaymentProvider;
import com.learning.payment_service.gateway.webhook.RazorpayWebhookHandler;
import com.learning.payment_service.gateway.webhook.StripeWebhookHandler;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole payment flow through the real use cases, JPA gateways and V1 migration on H2 (Oracle mode), with the
 * simulated PSP. It catches schema, mapping and state-machine mistakes; it does not replace a run against Oracle.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paymentflow;MODE=Oracle",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.connection-test-query=SELECT 1",
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.default_schema=PUBLIC",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V1__create_payment_schema.sql,classpath:db/migration/V2__outbox_relay.sql",
        "payment.jobs.enabled=false"
})
class PaymentFlowTest {

    static final String STRIPE_SECRET = "whsec_test";
    static final String RAZORPAY_SECRET = "rzp_test";

    final UUID order = UUID.randomUUID();
    final UUID customer = UUID.randomUUID();

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PaymentGateway gateway;
    @Autowired
    WebhookEventGateway events;
    SimulatedPaymentProvider stripe;
    SimulatedPaymentProvider razorpay;
    MutableClock clock;
    CreateIntentUsecase createIntent;
    ConfirmIntentUsecase confirm;
    CreateRefundUsecase refund;
    GetPaymentUsecase get;
    ReceiveWebhookUsecase receive;
    ProcessWebhookEventsUsecase process;
    ReconcileStuckIntentsUsecase reconcile;

    @BeforeEach
    void setUp() {
        // the Spring context (and its H2 database) is shared by every test, so start each one empty
        for (var table : List.of("payment_outbox", "webhook_events", "refunds", "payment_attempts", "payment_intents")) {
            jdbc.update("DELETE FROM " + table);
        }
        clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
        stripe = new SimulatedPaymentProvider(Provider.STRIPE);
        razorpay = new SimulatedPaymentProvider(Provider.RAZORPAY);

        var providers = new ProviderRegistry(List.<PaymentProvider>of(stripe, razorpay));
        var retry = new RetryingProviderCall(3, Duration.ZERO);
        var outbox = new OutboxService(gateway, JsonMapper.builder().build(), clock);
        var capture = new CaptureIntentService(gateway, outbox, clock);
        var fail = new FailIntentService(gateway, outbox, clock);
        var cancel = new CancelIntentService(gateway, outbox, clock);
        var requiresAction = new MarkIntentRequiresActionService(gateway, clock);
        var completeRefund = new CompleteRefundService(gateway, outbox, clock);
        var failRefund = new FailRefundService(gateway, clock);
        var handlers = new WebhookHandlers(List.of(
                new StripeWebhookHandler(STRIPE_SECRET, Duration.ofMinutes(5), clock),
                new RazorpayWebhookHandler(RAZORPAY_SECRET)));

        createIntent = new CreateIntentUsecase(gateway, providers, retry, fail, requiresAction, outbox, clock);
        confirm = new ConfirmIntentUsecase(gateway, providers, retry, capture, fail, clock);
        refund = new CreateRefundUsecase(gateway, providers, retry, completeRefund, failRefund, outbox, clock);
        get = new GetPaymentUsecase(gateway);
        receive = new ReceiveWebhookUsecase(handlers, events, clock);
        process = new ProcessWebhookEventsUsecase(events, gateway, handlers, cancel, capture, completeRefund, fail, failRefund, clock);
        reconcile = new ReconcileStuckIntentsUsecase(gateway, providers, cancel, capture, fail, clock);
    }

    // ---------------------------------------------------------------- create intent

    @Test
    void createReturnsRequiresActionWithClientSecretAndEmitsInitiated() {
        var result = create("key-1", "1299.00", "INR");

        assertThat(result.isCreated()).isTrue();
        assertThat(result.getIntent().status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(result.getIntent().provider()).isEqualTo(Provider.RAZORPAY); // INR routes to Razorpay
        assertThat(result.getIntent().providerIntentId()).startsWith("order_sim_");
        assertThat(result.getClientSecret()).endsWith("_secret_sim");
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED");
    }

    @Test
    void nonInrRoutesToStripe() {
        assertThat(create("key-usd", "10.00", "USD").getIntent().provider()).isEqualTo(Provider.STRIPE);
    }

    @Test
    void replayingTheSameKeyReturnsTheSameIntentWithoutADuplicate() {
        var first = create("key-1", "1299.00", "INR");
        var replay = create("key-1", "1299.00", "INR");

        assertThat(replay.isCreated()).isFalse();
        assertThat(replay.getIntent().id()).isEqualTo(first.getIntent().id());
        assertThat(replay.getClientSecret()).isEqualTo(first.getClientSecret());
        assertThat(count("payment_intents")).isEqualTo(1);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        create("key-1", "1299.00", "INR");

        assertThatThrownBy(() -> create("key-1", "1300.00", "INR"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void invalidAmountAndCurrencyAreRejected() {
        assertThatThrownBy(() -> create("k", "0", "INR"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_AMOUNT"));
        assertThatThrownBy(() -> create("k", "5", "inr"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_CURRENCY"));
    }

    @Test
    void anOrderThatIsAlreadyPaidCannotGetAnotherIntent() {
        var paid = create("key-1", "100.00", "INR");
        confirm(paid.getIntent().id(), "tok_ok");

        assertThatThrownBy(() -> create("key-2", "100.00", "INR"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ORDER_ALREADY_PAID"));
    }

    // ---------------------------------------------------------------- confirm

    @Test
    void successfulConfirmCapturesAndEmitsPaymentSuccess() {
        var intent = create("key-1", "250.00", "INR").getIntent();

        var confirmed = confirm(intent.id(), "tok_ok");

        assertThat(confirmed.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCESS");
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(outboxPayload("PAYMENT_SUCCESS")).contains("\"orderId\":\"" + order + "\"", "\"provider\":\"RAZORPAY\"");
    }

    @Test
    void confirmingACapturedPaymentAgainIsANoOp() {
        var intent = create("key-1", "250.00", "INR").getIntent();
        confirm(intent.id(), "tok_ok");

        var again = confirm(intent.id(), "tok_ok");

        assertThat(again.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCESS");
    }

    @Test
    void declinedCardFailsTheIntentAndEmitsPaymentFailed() {
        var intent = create("key-1", "250.00", "INR").getIntent();

        var confirmed = confirm(intent.id(), "tok_fail_card");

        assertThat(confirmed.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(confirmed.failureCode()).isEqualTo("card_declined");
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED", "PAYMENT_FAILED");
        assertThatThrownBy(() -> confirm(intent.id(), "tok_ok"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_PAYMENT_STATE"));
    }

    @Test
    void threeDSecureLeavesTheIntentWaitingForTheCustomer() {
        var intent = create("key-1", "250.00", "INR").getIntent();

        assertThat(confirm(intent.id(), "tok_3ds").status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED");
        assertThat(confirm(intent.id(), "tok_ok").status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(count("payment_attempts")).isEqualTo(2);
    }

    @Test
    void providerOutageIsA503AndLeavesTheIntentUntouched() {
        var intent = create("key-1", "250.00", "INR").getIntent();

        assertThatThrownBy(() -> confirm(intent.id(), "tok_transient"))
                .isInstanceOf(DependencyUnavailableException.class);

        assertThat(get.execute(new GetPaymentUsecaseRequest(intent.id(), null)).getIntent().status())
                .isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempts", String.class)).isEqualTo("ERROR");
    }

    @Test
    void confirmingAnUnknownPaymentIs404() {
        assertThatThrownBy(() -> confirm(UUID.randomUUID(), "tok_ok")).isInstanceOf(PaymentNotFoundException.class);
    }

    // ---------------------------------------------------------------- get

    @Test
    void anotherCustomerCannotReadAPayment() {
        var intent = create("key-1", "250.00", "INR").getIntent();

        assertThat(get.execute(new GetPaymentUsecaseRequest(intent.id(), customer)).getIntent().id()).isEqualTo(intent.id());
        assertThatThrownBy(() -> get.execute(new GetPaymentUsecaseRequest(intent.id(), UUID.randomUUID())))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    // ---------------------------------------------------------------- refunds

    @Test
    void partialThenRemainingRefundMovesTheIntentToRefunded() {
        capture("key-1", "100.00");

        var first = refund("refund_1", "30.00");
        assertThat(first.isCreated()).isTrue();
        assertThat(first.getRefund().status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(first.getIntent().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        var rest = refund("refund_2", null); // omitted amount = whatever is left
        assertThat(rest.getRefund().amount()).isEqualByComparingTo("70.00");
        assertThat(rest.getIntent().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(outboxTypes()).containsSubsequence("PAYMENT_SUCCESS", "REFUND_INITIATED", "REFUND_COMPLETED",
                "REFUND_INITIATED", "REFUND_COMPLETED");
    }

    @Test
    void refundCannotExceedTheCapturedAmount() {
        capture("key-1", "100.00");
        refund("refund_1", "80.00");

        assertThatThrownBy(() -> refund("refund_2", "30.00"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("REFUND_EXCEEDS_CAPTURED"));
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void aFullyRefundedOrderCannotBeRefundedAgain() {
        capture("key-1", "100.00");
        refund("refund_1", null);

        assertThatThrownBy(() -> refund("refund_2", "1.00"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("REFUND_EXCEEDS_CAPTURED"));
    }

    @Test
    void replayingARefundKeyReturnsTheSameRefund() {
        capture("key-1", "100.00");
        var first = refund("refund_1", "40.00");

        var replay = refund("refund_1", "40.00");

        assertThat(replay.isCreated()).isFalse();
        assertThat(replay.getRefund().id()).isEqualTo(first.getRefund().id());
        assertThat(count("refunds")).isEqualTo(1);
        assertThatThrownBy(() -> refund("refund_1", "50.00"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void anUnpaidOrderHasNothingToRefund() {
        create("key-1", "100.00", "INR");

        assertThatThrownBy(() -> refund("refund_1", "10.00"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PAYMENT_NOT_CAPTURED"));
    }

    // ---------------------------------------------------------------- webhooks

    @Test
    void webhookIsVerifiedStoredOnceAndThenAppliedByTheProcessor() {
        var intent = create("key-1", "100.00", "USD").getIntent(); // Stripe
        String body = stripeEvent("evt_1", "payment_intent.succeeded", intent.providerIntentId());

        assertThat(receive.execute(stripeRequest(body)).isDuplicate()).isFalse();
        assertThat(receive.execute(stripeRequest(body)).isDuplicate()).isTrue(); // PSP retry
        assertThat(count("webhook_events")).isEqualTo(1);
        assertThat(status(intent.id())).isEqualTo(PaymentStatus.REQUIRES_ACTION); // stored, not applied yet

        assertThat(process.execute(new ProcessWebhookEventsUsecaseRequest(10)).getProcessed()).isEqualTo(1);

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxTypes()).contains("PAYMENT_SUCCESS");
        assertThat(process.execute(new ProcessWebhookEventsUsecaseRequest(10)).getProcessed()).isZero();
    }

    @Test
    void webhookWithABadSignatureIsRejectedAndNotStored() {
        var intent = create("key-1", "100.00", "USD").getIntent();
        String body = stripeEvent("evt_1", "payment_intent.succeeded", intent.providerIntentId());
        var forged = new WebhookRequest(body, h -> "Stripe-Signature".equalsIgnoreCase(h) ? "t=1,v1=deadbeef" : null);

        assertThatThrownBy(() -> receive.execute(new ReceiveWebhookUsecaseRequest(Provider.STRIPE, forged)))
                .isInstanceOfSatisfying(InvalidWebhookException.class, e -> assertThat(e.isBadSignature()).isTrue());
        assertThat(count("webhook_events")).isZero();
    }

    @Test
    void failedWebhookFailsTheIntentAndSucceededAfterwardsDoesNotResurrectIt() {
        var intent = create("key-1", "100.00", "USD").getIntent();
        receive.execute(stripeRequest(stripeEvent("evt_1", "payment_intent.payment_failed", intent.providerIntentId())));
        receive.execute(stripeRequest(stripeEvent("evt_2", "payment_intent.succeeded", intent.providerIntentId())));

        process.execute(new ProcessWebhookEventsUsecaseRequest(10));

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.FAILED);
        assertThat(outboxTypes()).doesNotContain("PAYMENT_SUCCESS");
    }

    @Test
    void razorpayWebhookCapturesTheIntentByOrderId() {
        var intent = create("key-1", "100.00", "INR").getIntent(); // Razorpay
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\""
                + intent.providerIntentId() + "\"}}}}";

        receive.execute(new ReceiveWebhookUsecaseRequest(Provider.RAZORPAY, new WebhookRequest(body, h -> switch (h.toLowerCase()) {
            case "x-razorpay-signature" -> hmac(RAZORPAY_SECRET, body);
            case "x-razorpay-event-id" -> "evt_rzp_1";
            default -> null;
        })));
        process.execute(new ProcessWebhookEventsUsecaseRequest(10));

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void webhookForAnUnknownIntentIsRetriedThenDroppedAfterTheGracePeriod() {
        receive.execute(stripeRequest(stripeEvent("evt_x", "payment_intent.succeeded", "pi_unknown")));

        assertThat(process.execute(new ProcessWebhookEventsUsecaseRequest(10)).getProcessed()).isZero(); // left for later

        clock.advance(Duration.ofHours(2));
        assertThat(process.execute(new ProcessWebhookEventsUsecaseRequest(10)).getProcessed()).isEqualTo(1); // given up
    }

    @Test
    void refundProcessedWebhookCompletesAProcessingRefund() {
        capture("key-1", "100.00");
        var intent = gateway.findIntentsByOrder(order).getFirst();
        var processing = gateway.insertRefund(com.learning.payment_service.core.domain.Refund
                .initiated(intent, new BigDecimal("100.00"), "test", "refund_w", clock.offsetNow()).processing("re_1"));
        String body = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"re_1\"}}}}";

        receive.execute(new ReceiveWebhookUsecaseRequest(Provider.RAZORPAY, new WebhookRequest(body, h -> switch (h.toLowerCase()) {
            case "x-razorpay-signature" -> hmac(RAZORPAY_SECRET, body);
            case "x-razorpay-event-id" -> "evt_rzp_refund";
            default -> null;
        })));
        process.execute(new ProcessWebhookEventsUsecaseRequest(10));

        assertThat(gateway.findRefund(processing.id()).orElseThrow().status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(status(intent.id())).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(outboxTypes()).contains("REFUND_COMPLETED");
    }

    // ---------------------------------------------------------------- reconciliation

    @Test
    void reconciliationCapturesAnIntentThePspAlreadyCompleted() {
        var intent = create("key-1", "100.00", "USD").getIntent();
        stripe.confirm(new PaymentProvider.ConfirmCommand(intent.providerIntentId(), "tok_ok", "x")); // PSP paid, webhook lost

        assertThat(reconcile.execute(new ReconcileStuckIntentsUsecaseRequest(Duration.ofHours(1), 10)).getReconciled())
                .isZero(); // not stuck yet
        clock.advance(Duration.ofHours(2));
        assertThat(reconcile.execute(new ReconcileStuckIntentsUsecaseRequest(Duration.ofHours(1), 10)).getReconciled())
                .isEqualTo(1);

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxTypes()).contains("PAYMENT_SUCCESS");
    }

    @Test
    void reconciliationLeavesAPendingIntentAlone() {
        var intent = create("key-1", "100.00", "USD").getIntent();
        clock.advance(Duration.ofHours(2));

        assertThat(reconcile.execute(new ReconcileStuckIntentsUsecaseRequest(Duration.ofHours(1), 10)).getReconciled()).isZero();
        assertThat(status(intent.id())).isEqualTo(PaymentStatus.REQUIRES_ACTION);
    }

    // ---------------------------------------------------------------- outbox relay

    @Test
    void relayPublishesPendingEventsKeyedByOrderIdAndMarksThemSent() {
        create("key-relay", "1299.00", "INR");
        var sent = new java.util.ArrayList<String>();
        var poller = poller((key, type, payload) -> sent.add(key + "|" + type));

        assertThat(poller.publishBatch()).isEqualTo(1);
        assertThat(sent).containsExactly(order + "|PAYMENT_INITIATED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox", String.class)).isEqualTo("SENT");
        assertThat(poller.publishBatch()).isZero();
    }

    @Test
    void relayLeavesTheEventPendingWhenTheBrokerIsDown() {
        create("key-down", "1299.00", "INR");
        var poller = poller((key, type, payload) -> {
            throw new IllegalStateException("broker down");
        });

        assertThat(poller.publishBatch()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM payment_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void relayFailsAnEventTheBrokerWillNeverAccept() {
        create("key-bad", "1299.00", "INR");
        var poller = poller((key, type, payload) -> {
            throw new com.learning.payment_service.gateway.messaging.NonRetryablePublishException("too large", null);
        });

        poller.publishBatch();

        assertThat(jdbc.queryForObject("SELECT status FROM payment_outbox", String.class)).isEqualTo("FAILED");
    }

    // ---------------------------------------------------------------- PAYMENT_REQUESTED from Order Service

    @Test
    void paymentRequestedOpensTheIntentUnderTheOrderKeyAndRedeliveryChangesNothing() {
        var handler = requestedHandler();

        var first = handler.execute(new HandlePaymentRequestedUsecase.Request(paymentRequested("1299.00", "INR")));
        var again = handler.execute(new HandlePaymentRequestedUsecase.Request(paymentRequested("1299.00", "INR")));

        assertThat(first.outcome()).isEqualTo(HandlePaymentRequestedUsecase.Outcome.INTENT_OPEN);
        assertThat(again.outcome()).isEqualTo(HandlePaymentRequestedUsecase.Outcome.INTENT_OPEN);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM payment_intents", String.class)).isEqualTo("order_" + order);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents", Integer.class)).isEqualTo(1);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED");
    }

    @Test
    void paymentRequestedForAnAlreadyPaidOrderIsRefusedAndReportedAsPaymentFailed() {
        capture("key-first", "1299.00");

        var result = requestedHandler().execute(new HandlePaymentRequestedUsecase.Request(paymentRequested("1299.00", "INR")));

        assertThat(result.outcome()).isEqualTo(HandlePaymentRequestedUsecase.Outcome.REFUSED);
        var failed = outboxPayload("PAYMENT_FAILED");
        assertThat(failed).contains(order.toString()).contains("ORDER_ALREADY_PAID");
    }

    @Test
    void otherOrderEventsAreIgnored() {
        var result = requestedHandler().execute(new HandlePaymentRequestedUsecase.Request(
                "{\"eventType\":\"ORDER_CREATED\",\"data\":{}}"));

        assertThat(result.outcome()).isEqualTo(HandlePaymentRequestedUsecase.Outcome.IGNORED);
        assertThat(outboxTypes()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    @Autowired
    com.learning.payment_service.gateway.PaymentOutboxRepository outboxRepository;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    /** The relay under test; {@code publishBatch} needs the caller's transaction for the row lock. */
    PaymentOutboxPollerHarness poller(com.learning.payment_service.gateway.messaging.EventPublisher publisher) {
        var inner = new com.learning.payment_service.gateway.PaymentOutboxPoller(outboxRepository, publisher,
                new org.springframework.transaction.support.TransactionTemplate(txManager), JsonMapper.builder().build(),
                clock, 100);
        return () -> inner.runOnce();
    }

    interface PaymentOutboxPollerHarness {
        int publishBatch();
    }

    HandlePaymentRequestedUsecase requestedHandler() {
        var json = JsonMapper.builder().build();
        return new HandlePaymentRequestedUsecase(createIntent, new OutboxService(gateway, json, clock), json);
    }

    String paymentRequested(String amount, String currency) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"PAYMENT_REQUESTED\",\"data\":{\"orderId\":\""
                + order + "\",\"customerId\":\"" + customer + "\",\"amount\":" + amount + ",\"currency\":\""
                + currency + "\",\"provider\":null}}";
    }

    CreateIntentUsecaseResponse create(String key, String amount, String currency) {
        return createIntent.execute(new CreateIntentUsecaseRequest(key, order, customer, new BigDecimal(amount), currency, null));
    }

    com.learning.payment_service.core.domain.PaymentIntent confirm(UUID id, String token) {
        return confirm.execute(new ConfirmIntentUsecaseRequest(id, token)).getIntent();
    }

    void capture(String key, String amount) {
        confirm(create(key, amount, "INR").getIntent().id(), "tok_ok");
    }

    CreateRefundUsecaseResponse refund(String key, String amount) {
        return refund.execute(new CreateRefundUsecaseRequest(key, order, amount == null ? null : new BigDecimal(amount), "test"));
    }

    PaymentStatus status(UUID id) {
        return get.execute(new GetPaymentUsecaseRequest(id, null)).getIntent().status();
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    List<String> outboxTypes() {
        return jdbc.queryForList("SELECT event_type FROM payment_outbox ORDER BY id", String.class);
    }

    String outboxPayload(String type) {
        return jdbc.queryForObject("SELECT payload FROM payment_outbox WHERE event_type = ?", String.class, type);
    }

    String stripeEvent(String id, String type, String providerIntentId) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + providerIntentId
                + "\",\"last_payment_error\":{\"code\":\"card_declined\",\"message\":\"declined\"}}}}";
    }

    ReceiveWebhookUsecaseRequest stripeRequest(String body) {
        long t = clock.instant().getEpochSecond();
        String signature = "t=" + t + ",v1=" + hmac(STRIPE_SECRET, t + "." + body);
        return new ReceiveWebhookUsecaseRequest(Provider.STRIPE,
                new WebhookRequest(body, h -> "Stripe-Signature".equalsIgnoreCase(h) ? signature : null));
    }

    static String hmac(String secret, String message) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        java.time.OffsetDateTime offsetNow() {
            return java.time.OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
