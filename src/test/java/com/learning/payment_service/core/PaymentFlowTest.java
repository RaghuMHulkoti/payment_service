package com.learning.payment_service.core;

import com.learning.payment_service.core.domain.PaymentStatus;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.domain.RefundStatus;
import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.provider.RetryingProviderCall;
import com.learning.payment_service.core.provider.WebhookHandler.WebhookRequest;
import com.learning.payment_service.core.provider.WebhookHandlers;
import com.learning.payment_service.core.service.OutboxWriter;
import com.learning.payment_service.core.service.PaymentStateService;
import com.learning.payment_service.core.usecase.ConfirmIntentUsecase;
import com.learning.payment_service.core.usecase.CreateIntentUsecase;
import com.learning.payment_service.core.usecase.CreateRefundUsecase;
import com.learning.payment_service.core.usecase.GetPaymentUsecase;
import com.learning.payment_service.core.usecase.ProcessWebhookEventsUsecase;
import com.learning.payment_service.core.usecase.ReceiveWebhookUsecase;
import com.learning.payment_service.core.usecase.ReconcileStuckIntentsUsecase;
import com.learning.payment_service.exception.BusinessRuleViolationException;
import com.learning.payment_service.exception.DependencyUnavailableException;
import com.learning.payment_service.exception.InvalidWebhookException;
import com.learning.payment_service.exception.PaymentNotFoundException;
import com.learning.payment_service.gateway.JdbcPaymentGateway;
import com.learning.payment_service.gateway.JdbcWebhookEventGateway;
import com.learning.payment_service.gateway.provider.SimulatedPaymentProvider;
import com.learning.payment_service.gateway.webhook.RazorpayWebhookHandler;
import com.learning.payment_service.gateway.webhook.StripeWebhookHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
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
 * The whole payment flow through the real use cases, gateway SQL and V1 migration on H2 (Oracle mode), with the
 * simulated PSP. It catches schema, SQL and state-machine mistakes; it does not replace a run against Oracle.
 */
class PaymentFlowTest {

    static final String STRIPE_SECRET = "whsec_test";
    static final String RAZORPAY_SECRET = "rzp_test";

    final UUID order = UUID.randomUUID();
    final UUID customer = UUID.randomUUID();

    JdbcTemplate jdbc;
    JdbcPaymentGateway gateway;
    JdbcWebhookEventGateway events;
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
        // One shared connection: H2 caches IN (...) check lists against the session that created them, so the
        // session that ran the migration must stay open (DriverManagerDataSource would close it).
        var ds = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_payment_schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        gateway = new JdbcPaymentGateway(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
        events = new JdbcWebhookEventGateway(jdbc);
        clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));
        stripe = new SimulatedPaymentProvider(Provider.STRIPE);
        razorpay = new SimulatedPaymentProvider(Provider.RAZORPAY);

        var providers = new ProviderRegistry(List.<PaymentProvider>of(stripe, razorpay));
        var retry = new RetryingProviderCall(3, Duration.ZERO);
        var outbox = new OutboxWriter(gateway, JsonMapper.builder().build(), clock);
        var state = new PaymentStateService(gateway, outbox, clock);
        var handlers = new WebhookHandlers(List.of(
                new StripeWebhookHandler(STRIPE_SECRET, Duration.ofMinutes(5), clock),
                new RazorpayWebhookHandler(RAZORPAY_SECRET)));

        createIntent = new CreateIntentUsecase(gateway, providers, retry, state, outbox, clock);
        confirm = new ConfirmIntentUsecase(gateway, providers, retry, state, clock);
        refund = new CreateRefundUsecase(gateway, providers, retry, state, outbox, clock);
        get = new GetPaymentUsecase(gateway);
        receive = new ReceiveWebhookUsecase(handlers, events, clock);
        process = new ProcessWebhookEventsUsecase(events, gateway, handlers, state, clock);
        reconcile = new ReconcileStuckIntentsUsecase(gateway, providers, state, clock);
    }

    // ---------------------------------------------------------------- create intent

    @Test
    void createReturnsRequiresActionWithClientSecretAndEmitsInitiated() {
        var result = create("key-1", "1299.00", "INR");

        assertThat(result.created()).isTrue();
        assertThat(result.intent().status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(result.intent().provider()).isEqualTo(Provider.RAZORPAY); // INR routes to Razorpay
        assertThat(result.intent().providerIntentId()).startsWith("order_sim_");
        assertThat(result.clientSecret()).endsWith("_secret_sim");
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED");
    }

    @Test
    void nonInrRoutesToStripe() {
        assertThat(create("key-usd", "10.00", "USD").intent().provider()).isEqualTo(Provider.STRIPE);
    }

    @Test
    void replayingTheSameKeyReturnsTheSameIntentWithoutADuplicate() {
        var first = create("key-1", "1299.00", "INR");
        var replay = create("key-1", "1299.00", "INR");

        assertThat(replay.created()).isFalse();
        assertThat(replay.intent().id()).isEqualTo(first.intent().id());
        assertThat(replay.clientSecret()).isEqualTo(first.clientSecret());
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
        confirm(paid.intent().id(), "tok_ok");

        assertThatThrownBy(() -> create("key-2", "100.00", "INR"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("ORDER_ALREADY_PAID"));
    }

    // ---------------------------------------------------------------- confirm

    @Test
    void successfulConfirmCapturesAndEmitsPaymentSuccess() {
        var intent = create("key-1", "250.00", "INR").intent();

        var confirmed = confirm(intent.id(), "tok_ok");

        assertThat(confirmed.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCESS");
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(outboxPayload("PAYMENT_SUCCESS")).contains("\"orderId\":\"" + order + "\"", "\"provider\":\"RAZORPAY\"");
    }

    @Test
    void confirmingACapturedPaymentAgainIsANoOp() {
        var intent = create("key-1", "250.00", "INR").intent();
        confirm(intent.id(), "tok_ok");

        var again = confirm(intent.id(), "tok_ok");

        assertThat(again.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED", "PAYMENT_SUCCESS");
    }

    @Test
    void declinedCardFailsTheIntentAndEmitsPaymentFailed() {
        var intent = create("key-1", "250.00", "INR").intent();

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
        var intent = create("key-1", "250.00", "INR").intent();

        assertThat(confirm(intent.id(), "tok_3ds").status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(outboxTypes()).containsExactly("PAYMENT_INITIATED");
        assertThat(confirm(intent.id(), "tok_ok").status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(count("payment_attempts")).isEqualTo(2);
    }

    @Test
    void providerOutageIsA503AndLeavesTheIntentUntouched() {
        var intent = create("key-1", "250.00", "INR").intent();

        assertThatThrownBy(() -> confirm(intent.id(), "tok_transient"))
                .isInstanceOf(DependencyUnavailableException.class);

        assertThat(get.execute(new GetPaymentUsecase.Request(intent.id(), null)).intent().status())
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
        var intent = create("key-1", "250.00", "INR").intent();

        assertThat(get.execute(new GetPaymentUsecase.Request(intent.id(), customer)).intent().id()).isEqualTo(intent.id());
        assertThatThrownBy(() -> get.execute(new GetPaymentUsecase.Request(intent.id(), UUID.randomUUID())))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    // ---------------------------------------------------------------- refunds

    @Test
    void partialThenRemainingRefundMovesTheIntentToRefunded() {
        capture("key-1", "100.00");

        var first = refund("refund_1", "30.00");
        assertThat(first.created()).isTrue();
        assertThat(first.refund().status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(first.intent().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        var rest = refund("refund_2", null); // omitted amount = whatever is left
        assertThat(rest.refund().amount()).isEqualByComparingTo("70.00");
        assertThat(rest.intent().status()).isEqualTo(PaymentStatus.REFUNDED);
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

        assertThat(replay.created()).isFalse();
        assertThat(replay.refund().id()).isEqualTo(first.refund().id());
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
        var intent = create("key-1", "100.00", "USD").intent(); // Stripe
        String body = stripeEvent("evt_1", "payment_intent.succeeded", intent.providerIntentId());

        assertThat(receive.execute(stripeRequest(body)).duplicate()).isFalse();
        assertThat(receive.execute(stripeRequest(body)).duplicate()).isTrue(); // PSP retry
        assertThat(count("webhook_events")).isEqualTo(1);
        assertThat(status(intent.id())).isEqualTo(PaymentStatus.REQUIRES_ACTION); // stored, not applied yet

        assertThat(process.execute(new ProcessWebhookEventsUsecase.Request(10)).processed()).isEqualTo(1);

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxTypes()).contains("PAYMENT_SUCCESS");
        assertThat(process.execute(new ProcessWebhookEventsUsecase.Request(10)).processed()).isZero();
    }

    @Test
    void webhookWithABadSignatureIsRejectedAndNotStored() {
        var intent = create("key-1", "100.00", "USD").intent();
        String body = stripeEvent("evt_1", "payment_intent.succeeded", intent.providerIntentId());
        var forged = new WebhookRequest(body, h -> "Stripe-Signature".equalsIgnoreCase(h) ? "t=1,v1=deadbeef" : null);

        assertThatThrownBy(() -> receive.execute(new ReceiveWebhookUsecase.Request(Provider.STRIPE, forged)))
                .isInstanceOfSatisfying(InvalidWebhookException.class, e -> assertThat(e.isBadSignature()).isTrue());
        assertThat(count("webhook_events")).isZero();
    }

    @Test
    void failedWebhookFailsTheIntentAndSucceededAfterwardsDoesNotResurrectIt() {
        var intent = create("key-1", "100.00", "USD").intent();
        receive.execute(stripeRequest(stripeEvent("evt_1", "payment_intent.payment_failed", intent.providerIntentId())));
        receive.execute(stripeRequest(stripeEvent("evt_2", "payment_intent.succeeded", intent.providerIntentId())));

        process.execute(new ProcessWebhookEventsUsecase.Request(10));

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.FAILED);
        assertThat(outboxTypes()).doesNotContain("PAYMENT_SUCCESS");
    }

    @Test
    void razorpayWebhookCapturesTheIntentByOrderId() {
        var intent = create("key-1", "100.00", "INR").intent(); // Razorpay
        String body = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\",\"order_id\":\""
                + intent.providerIntentId() + "\"}}}}";

        receive.execute(new ReceiveWebhookUsecase.Request(Provider.RAZORPAY, new WebhookRequest(body, h -> switch (h.toLowerCase()) {
            case "x-razorpay-signature" -> hmac(RAZORPAY_SECRET, body);
            case "x-razorpay-event-id" -> "evt_rzp_1";
            default -> null;
        })));
        process.execute(new ProcessWebhookEventsUsecase.Request(10));

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void webhookForAnUnknownIntentIsRetriedThenDroppedAfterTheGracePeriod() {
        receive.execute(stripeRequest(stripeEvent("evt_x", "payment_intent.succeeded", "pi_unknown")));

        assertThat(process.execute(new ProcessWebhookEventsUsecase.Request(10)).processed()).isZero(); // left for later

        clock.advance(Duration.ofHours(2));
        assertThat(process.execute(new ProcessWebhookEventsUsecase.Request(10)).processed()).isEqualTo(1); // given up
    }

    @Test
    void refundProcessedWebhookCompletesAProcessingRefund() {
        capture("key-1", "100.00");
        var intent = gateway.findIntentsByOrder(order).getFirst();
        var processing = gateway.insertRefund(com.learning.payment_service.core.domain.Refund
                .initiated(intent, new BigDecimal("100.00"), "test", "refund_w", clock.offsetNow()).processing("re_1"));
        String body = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"re_1\"}}}}";

        receive.execute(new ReceiveWebhookUsecase.Request(Provider.RAZORPAY, new WebhookRequest(body, h -> switch (h.toLowerCase()) {
            case "x-razorpay-signature" -> hmac(RAZORPAY_SECRET, body);
            case "x-razorpay-event-id" -> "evt_rzp_refund";
            default -> null;
        })));
        process.execute(new ProcessWebhookEventsUsecase.Request(10));

        assertThat(gateway.findRefund(processing.id()).orElseThrow().status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(status(intent.id())).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(outboxTypes()).contains("REFUND_COMPLETED");
    }

    // ---------------------------------------------------------------- reconciliation

    @Test
    void reconciliationCapturesAnIntentThePspAlreadyCompleted() {
        var intent = create("key-1", "100.00", "USD").intent();
        stripe.confirm(new PaymentProvider.ConfirmCommand(intent.providerIntentId(), "tok_ok", "x")); // PSP paid, webhook lost

        assertThat(reconcile.execute(new ReconcileStuckIntentsUsecase.Request(Duration.ofHours(1), 10)).reconciled())
                .isZero(); // not stuck yet
        clock.advance(Duration.ofHours(2));
        assertThat(reconcile.execute(new ReconcileStuckIntentsUsecase.Request(Duration.ofHours(1), 10)).reconciled())
                .isEqualTo(1);

        assertThat(status(intent.id())).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outboxTypes()).contains("PAYMENT_SUCCESS");
    }

    @Test
    void reconciliationLeavesAPendingIntentAlone() {
        var intent = create("key-1", "100.00", "USD").intent();
        clock.advance(Duration.ofHours(2));

        assertThat(reconcile.execute(new ReconcileStuckIntentsUsecase.Request(Duration.ofHours(1), 10)).reconciled()).isZero();
        assertThat(status(intent.id())).isEqualTo(PaymentStatus.REQUIRES_ACTION);
    }

    // ---------------------------------------------------------------- helpers

    CreateIntentUsecase.Response create(String key, String amount, String currency) {
        return createIntent.execute(new CreateIntentUsecase.Request(key, order, customer, new BigDecimal(amount), currency, null));
    }

    com.learning.payment_service.core.domain.PaymentIntent confirm(UUID id, String token) {
        return confirm.execute(new ConfirmIntentUsecase.Request(id, token)).intent();
    }

    void capture(String key, String amount) {
        confirm(create(key, amount, "INR").intent().id(), "tok_ok");
    }

    CreateRefundUsecase.Response refund(String key, String amount) {
        return refund.execute(new CreateRefundUsecase.Request(key, order, amount == null ? null : new BigDecimal(amount), "test"));
    }

    PaymentStatus status(UUID id) {
        return get.execute(new GetPaymentUsecase.Request(id, null)).intent().status();
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

    ReceiveWebhookUsecase.Request stripeRequest(String body) {
        long t = clock.instant().getEpochSecond();
        String signature = "t=" + t + ",v1=" + hmac(STRIPE_SECRET, t + "." + body);
        return new ReceiveWebhookUsecase.Request(Provider.STRIPE,
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
