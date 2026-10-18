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
import com.learning.payment_service.gateway.PaymentOutboxRepository;
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
    public ProviderRegistry providerRegistry() {
        return new ProviderRegistry(List.of(
                new SimulatedPaymentProvider(Provider.STRIPE), new SimulatedPaymentProvider(Provider.RAZORPAY)));
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

    // =========================================================================
    // Infrastructure
    // =========================================================================

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
