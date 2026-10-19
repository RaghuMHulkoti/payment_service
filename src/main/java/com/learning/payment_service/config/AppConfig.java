package com.learning.payment_service.config;

import com.learning.payment_service.core.service.outbox.OutboxService;
import com.learning.payment_service.core.service.intent.requiresaction.MarkIntentRequiresActionService;
import com.learning.payment_service.core.service.intent.capture.CaptureIntentService;
import com.learning.payment_service.core.service.intent.fail.FailIntentService;
import com.learning.payment_service.core.service.intent.cancel.CancelIntentService;
import com.learning.payment_service.core.service.refund.complete.CompleteRefundService;
import com.learning.payment_service.core.service.refund.fail.FailRefundService;
import com.learning.payment_service.core.processor.ConfirmIntentProcessor;
import com.learning.payment_service.core.processor.CreateIntentProcessor;
import com.learning.payment_service.core.processor.CreateRefundProcessor;
import com.learning.payment_service.core.processor.GetPaymentProcessor;
import com.learning.payment_service.core.processor.ReceiveWebhookProcessor;
import com.learning.payment_service.core.domain.Provider;
import com.learning.payment_service.core.provider.PaymentProvider;
import com.learning.payment_service.core.provider.ProviderRegistry;
import com.learning.payment_service.core.provider.RetryingProviderCall;
import com.learning.payment_service.core.provider.WebhookHandler;
import com.learning.payment_service.core.provider.WebhookHandlers;
import com.learning.payment_service.core.usecase.ConfirmIntentUsecase;
import com.learning.payment_service.core.usecase.CreateIntentUsecase;
import com.learning.payment_service.core.usecase.CreateRefundUsecase;
import com.learning.payment_service.core.usecase.GetPaymentUsecase;
import com.learning.payment_service.core.usecase.ProcessWebhookEventsUsecase;
import com.learning.payment_service.core.usecase.ReceiveWebhookUsecase;
import com.learning.payment_service.core.usecase.ReconcileStuckIntentsUsecase;
import com.learning.payment_service.gateway.JpaPaymentGateway;
import com.learning.payment_service.gateway.JpaWebhookEventGateway;
import com.learning.payment_service.gateway.PaymentAttemptRepository;
import com.learning.payment_service.gateway.PaymentIntentRepository;
import com.learning.payment_service.gateway.PaymentOutboxPoller;
import com.learning.payment_service.gateway.PaymentOutboxRepository;
import com.learning.payment_service.core.processor.GetCheckoutProcessor;
import com.learning.payment_service.core.processor.VerifyRazorpayPaymentProcessor;
import com.learning.payment_service.core.usecase.GetCheckoutUsecase;
import com.learning.payment_service.core.usecase.VerifyRazorpayPaymentUsecase;
import com.learning.payment_service.gateway.provider.RazorpayPaymentProvider;
import org.springframework.beans.factory.ObjectProvider;
import com.learning.payment_service.core.usecase.HandleOrderEndedUsecase;
import com.learning.payment_service.core.usecase.HandlePaymentRequestedUsecase;
import com.learning.payment_service.gateway.provider.StripePaymentProvider;
import com.stripe.StripeClient;
import com.learning.payment_service.gateway.messaging.EventPublisher;
import com.learning.payment_service.gateway.messaging.OrderEventListener;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import com.learning.payment_service.gateway.messaging.KafkaEventPublisher;
import com.learning.payment_service.gateway.messaging.LoggingEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import com.learning.payment_service.gateway.RefundRepository;
import com.learning.payment_service.gateway.WebhookEventRepository;
import com.learning.payment_service.gateway.PaymentGateway;
import com.learning.payment_service.gateway.ReconciliationJob;
import com.learning.payment_service.gateway.WebhookEventGateway;
import com.learning.payment_service.gateway.WebhookProcessorJob;
import com.learning.payment_service.gateway.provider.SimulatedPaymentProvider;
import com.learning.payment_service.gateway.webhook.RazorpayWebhookHandler;
import com.learning.payment_service.gateway.webhook.StripeWebhookHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

/**
 * Wires the core layers by hand so that processor, use case and service classes stay
 * free of Spring stereotype annotations.
 */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
@EnableScheduling
public class AppConfig {

    // =========================================================================
    // Processors
    // =========================================================================

    @Bean
    public CreateIntentProcessor createIntentProcessor(CreateIntentUsecase usecase) {
        return new CreateIntentProcessor(usecase);
    }

    @Bean
    public ConfirmIntentProcessor confirmIntentProcessor(ConfirmIntentUsecase usecase) {
        return new ConfirmIntentProcessor(usecase);
    }

    @Bean
    public CreateRefundProcessor createRefundProcessor(CreateRefundUsecase usecase) {
        return new CreateRefundProcessor(usecase);
    }

    @Bean
    public GetPaymentProcessor getPaymentProcessor(GetPaymentUsecase usecase) {
        return new GetPaymentProcessor(usecase);
    }

    @Bean
    public ReceiveWebhookProcessor receiveWebhookProcessor(ReceiveWebhookUsecase usecase) {
        return new ReceiveWebhookProcessor(usecase);
    }

    // =========================================================================
    // Use cases
    // =========================================================================

    @Bean
    public CreateIntentUsecase createIntentUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry, FailIntentService failIntentService, MarkIntentRequiresActionService markIntentRequiresActionService, OutboxService outboxService, Clock clock) {
        return new CreateIntentUsecase(gateway, providers, retry, failIntentService, markIntentRequiresActionService, outboxService, clock);
    }

    @Bean
    public CreateRefundUsecase createRefundUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry, CompleteRefundService completeRefundService, FailRefundService failRefundService, OutboxService outboxService, Clock clock) {
        return new CreateRefundUsecase(gateway, providers, retry, completeRefundService, failRefundService, outboxService, clock);
    }

    @Bean
    public ConfirmIntentUsecase confirmIntentUsecase(PaymentGateway gateway, ProviderRegistry providers, RetryingProviderCall retry, CaptureIntentService captureIntentService, FailIntentService failIntentService, Clock clock) {
        return new ConfirmIntentUsecase(gateway, providers, retry, captureIntentService, failIntentService, clock);
    }

    @Bean
    public GetPaymentUsecase getPaymentUsecase(PaymentGateway gateway) {
        return new GetPaymentUsecase(gateway);
    }

    @Bean
    public ReceiveWebhookUsecase receiveWebhookUsecase(WebhookHandlers handlers, WebhookEventGateway events, Clock clock) {
        return new ReceiveWebhookUsecase(handlers, events, clock);
    }

    @Bean
    public ProcessWebhookEventsUsecase processWebhookEventsUsecase(WebhookEventGateway events, PaymentGateway gateway, WebhookHandlers handlers, CancelIntentService cancelIntentService, CaptureIntentService captureIntentService, CompleteRefundService completeRefundService, FailIntentService failIntentService, FailRefundService failRefundService, Clock clock) {
        return new ProcessWebhookEventsUsecase(events, gateway, handlers, cancelIntentService, captureIntentService, completeRefundService, failIntentService, failRefundService, clock);
    }

    @Bean
    public ReconcileStuckIntentsUsecase reconcileStuckIntentsUsecase(PaymentGateway gateway, ProviderRegistry providers, CancelIntentService cancelIntentService, CaptureIntentService captureIntentService, FailIntentService failIntentService, Clock clock) {
        return new ReconcileStuckIntentsUsecase(gateway, providers, cancelIntentService, captureIntentService, failIntentService, clock);
    }

    // =========================================================================
    // Services
    // =========================================================================

    @Bean
    public OutboxService outboxService(PaymentGateway gateway, JsonMapper mapper, Clock clock) {
        return new OutboxService(gateway, mapper, clock);
    }

    @Bean
    public CaptureIntentService captureIntentService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        return new CaptureIntentService(gateway, outboxService, clock);
    }

    @Bean
    public FailIntentService failIntentService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        return new FailIntentService(gateway, outboxService, clock);
    }

    @Bean
    public CancelIntentService cancelIntentService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        return new CancelIntentService(gateway, outboxService, clock);
    }

    @Bean
    public MarkIntentRequiresActionService markIntentRequiresActionService(PaymentGateway gateway, Clock clock) {
        return new MarkIntentRequiresActionService(gateway, clock);
    }

    @Bean
    public CompleteRefundService completeRefundService(PaymentGateway gateway, OutboxService outboxService, Clock clock) {
        return new CompleteRefundService(gateway, outboxService, clock);
    }

    @Bean
    public FailRefundService failRefundService(PaymentGateway gateway, Clock clock) {
        return new FailRefundService(gateway, clock);
    }

    // =========================================================================
    // Gateways
    // =========================================================================

    @Bean
    public PaymentGateway paymentGateway(PaymentIntentRepository intents, PaymentAttemptRepository attempts,
                                         RefundRepository refunds, PaymentOutboxRepository outbox,
                                         PlatformTransactionManager transactionManager) {
        return new JpaPaymentGateway(intents, attempts, refunds, outbox, new TransactionTemplate(transactionManager));
    }

    @Bean
    public WebhookEventGateway webhookEventGateway(WebhookEventRepository repository) {
        return new JpaWebhookEventGateway(repository);
    }

    /**
     * Both providers are simulated until live Stripe/Razorpay adapters are written; they implement
     * {@link PaymentProvider} and replace these beans without touching the use cases.
     */
    @Bean
    public ProviderRegistry providerRegistry(PaymentProperties properties, ObjectProvider<RazorpayPaymentProvider> razorpay) {
        // Stripe talks to the real API (test mode with an sk_test key) once a key is configured; otherwise simulated
        PaymentProvider stripe = properties.stripe().apiKey().isBlank()
                ? new SimulatedPaymentProvider(Provider.STRIPE)
                : new StripePaymentProvider(new StripeClient(properties.stripe().apiKey()));
        // Razorpay Standard Checkout once both keys are configured; otherwise simulated
        RazorpayPaymentProvider realRazorpay = razorpay.getIfAvailable();
        PaymentProvider razorpayProvider = realRazorpay != null ? realRazorpay : new SimulatedPaymentProvider(Provider.RAZORPAY);
        return new ProviderRegistry(List.of(stripe, razorpayProvider));
    }

    @Bean
    @ConditionalOnExpression("!'${payment.razorpay.key-id:}'.isEmpty() and !'${payment.razorpay.key-secret:}'.isEmpty()")
    public RazorpayPaymentProvider razorpayPaymentProvider(PaymentProperties properties) {
        return new RazorpayPaymentProvider(properties.razorpay().keyId(), properties.razorpay().keySecret());
    }

    @Bean
    public GetCheckoutUsecase getCheckoutUsecase(PaymentGateway gateway, ObjectProvider<RazorpayPaymentProvider> razorpay) {
        return new GetCheckoutUsecase(gateway, razorpay.getIfAvailable());
    }

    @Bean
    public VerifyRazorpayPaymentUsecase verifyRazorpayPaymentUsecase(PaymentGateway gateway,
                                                                     ObjectProvider<RazorpayPaymentProvider> razorpay,
                                                                     CaptureIntentService captureIntentService, Clock clock) {
        return new VerifyRazorpayPaymentUsecase(gateway, razorpay.getIfAvailable(), captureIntentService, clock);
    }

    @Bean
    public GetCheckoutProcessor getCheckoutProcessor(GetCheckoutUsecase usecase) {
        return new GetCheckoutProcessor(usecase);
    }

    @Bean
    public VerifyRazorpayPaymentProcessor verifyRazorpayPaymentProcessor(VerifyRazorpayPaymentUsecase usecase) {
        return new VerifyRazorpayPaymentProcessor(usecase);
    }

    @Bean
    public WebhookHandlers webhookHandlers(PaymentProperties properties, Clock clock) {
        List<WebhookHandler> handlers = List.of(
                new StripeWebhookHandler(properties.stripe().webhookSecret(),
                        properties.stripe().webhookTolerance(), clock),
                new RazorpayWebhookHandler(properties.razorpay().webhookSecret()));
        return new WebhookHandlers(handlers);
    }

    @Bean
    public RetryingProviderCall retryingProviderCall(PaymentProperties properties) {
        return new RetryingProviderCall(properties.providerMaxAttempts(), properties.providerBackoff());
    }

    // =========================================================================
    // Jobs
    // =========================================================================

    @Bean
    @ConditionalOnProperty(name = "payment.jobs.enabled", havingValue = "true", matchIfMissing = true)
    public WebhookProcessorJob webhookProcessorJob(ProcessWebhookEventsUsecase usecase) {
        return new WebhookProcessorJob(usecase);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.jobs.enabled", havingValue = "true", matchIfMissing = true)
    public ReconciliationJob reconciliationJob(ReconcileStuckIntentsUsecase usecase, PaymentProperties properties) {
        return new ReconciliationJob(usecase, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.jobs.enabled", havingValue = "true", matchIfMissing = true)
    public PaymentOutboxPoller paymentOutboxPoller(PaymentOutboxRepository outbox, EventPublisher publisher,
                                                   PlatformTransactionManager transactionManager, JsonMapper json,
                                                   Clock clock, @Value("${payment.outbox.batch-size:100}") int batchSize) {
        return new PaymentOutboxPoller(outbox, publisher, new TransactionTemplate(transactionManager), json, clock,
                batchSize);
    }

    // =========================================================================
    // Events
    // =========================================================================

    @Bean
    public HandlePaymentRequestedUsecase handlePaymentRequestedUsecase(CreateIntentUsecase createIntent,
                                                                       OutboxService outboxService, JsonMapper json) {
        return new HandlePaymentRequestedUsecase(createIntent, outboxService, json);
    }

    @Bean
    public HandleOrderEndedUsecase handleOrderEndedUsecase(PaymentGateway gateway, ProviderRegistry providers,
                                                           RetryingProviderCall retry, CancelIntentService cancelIntent,
                                                           JsonMapper json) {
        return new HandleOrderEndedUsecase(gateway, providers, retry, cancelIntent, json);
    }

    /** Off by default, so the service runs without a broker (the REST intent endpoint keeps working). */
    @Bean
    @ConditionalOnProperty(name = "payment.events.consumer-enabled", havingValue = "true")
    public OrderEventListener orderEventListener(HandlePaymentRequestedUsecase requested, HandleOrderEndedUsecase ended) {
        return new OrderEventListener(requested, ended);
    }

    /**
     * Creates the topic this service owns at startup, with an explicit small partition count so the cluster quota holds
     * (free tier: 10 topics, 64 partitions in total). Replication is left to the broker default. The dead-letter topic
     * of the order events it consumes, {@code domain.order.events.dlq}, is created by Inventory Service (same name).
     */
    @Bean
    @ConditionalOnExpression("('${payment.events.publisher:log}' == 'kafka' or '${payment.events.consumer-enabled:false}' == 'true')"
            + " and '${payment.events.create-topics:true}' == 'true'")
    public KafkaAdmin.NewTopics paymentEventTopics(
            @Value("${payment.events.topic:domain.payment.events}") String paymentTopic,
            @Value("${payment.events.partitions:3}") int partitions) {
        return new KafkaAdmin.NewTopics(ownedTopics(paymentTopic, partitions).toArray(NewTopic[]::new));
    }

    static List<NewTopic> ownedTopics(String paymentTopic, int partitions) {
        return List.of(TopicBuilder.name(paymentTopic).partitions(partitions).build());
    }

    /** Retries 50ms, 250ms, 1.25s (TDD §13.7), then parks the event on {@code <topic>.dlq} and moves on. */
    @Bean
    @ConditionalOnProperty(name = "payment.events.consumer-enabled", havingValue = "true")
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(50);
        backOff.setMultiplier(5);
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, e) -> new TopicPartition(record.topic() + ".dlq", -1));
        return new DefaultErrorHandler(recoverer, backOff);
    }

    /** {@code payment.events.publisher=kafka} sends to the broker; the default only logs. */
    @Bean
    @ConditionalOnProperty(name = "payment.events.publisher", havingValue = "kafka")
    public EventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                              @Value("${payment.events.topic:domain.payment.events}") String topic) {
        return new KafkaEventPublisher(kafkaTemplate, topic);
    }

    @Bean
    @ConditionalOnProperty(name = "payment.events.publisher", havingValue = "log", matchIfMissing = true)
    public EventPublisher loggingEventPublisher() {
        return new LoggingEventPublisher();
    }

    // =========================================================================
    // Infrastructure
    // =========================================================================

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
