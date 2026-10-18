package com.learning.payment_service.config;

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
import com.learning.payment_service.core.service.OutboxWriter;
import com.learning.payment_service.core.service.PaymentStateService;
import com.learning.payment_service.core.usecase.ConfirmIntentUsecase;
import com.learning.payment_service.core.usecase.CreateIntentUsecase;
import com.learning.payment_service.core.usecase.CreateRefundUsecase;
import com.learning.payment_service.core.usecase.GetPaymentUsecase;
import com.learning.payment_service.core.usecase.ProcessWebhookEventsUsecase;
import com.learning.payment_service.core.usecase.ReceiveWebhookUsecase;
import com.learning.payment_service.core.usecase.ReconcileStuckIntentsUsecase;
import com.learning.payment_service.gateway.JdbcPaymentGateway;
import com.learning.payment_service.gateway.JdbcWebhookEventGateway;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    public CreateIntentUsecase createIntentUsecase(PaymentGateway gateway, ProviderRegistry providers,
                                                   RetryingProviderCall retry, PaymentStateService state,
                                                   OutboxWriter outbox, Clock clock) {
        return new CreateIntentUsecase(gateway, providers, retry, state, outbox, clock);
    }

    @Bean
    public ConfirmIntentUsecase confirmIntentUsecase(PaymentGateway gateway, ProviderRegistry providers,
                                                     RetryingProviderCall retry, PaymentStateService state,
                                                     Clock clock) {
        return new ConfirmIntentUsecase(gateway, providers, retry, state, clock);
    }

    @Bean
    public CreateRefundUsecase createRefundUsecase(PaymentGateway gateway, ProviderRegistry providers,
                                                   RetryingProviderCall retry, PaymentStateService state,
                                                   OutboxWriter outbox, Clock clock) {
        return new CreateRefundUsecase(gateway, providers, retry, state, outbox, clock);
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
    public ProcessWebhookEventsUsecase processWebhookEventsUsecase(WebhookEventGateway events, PaymentGateway gateway,
                                                                   WebhookHandlers handlers, PaymentStateService state,
                                                                   Clock clock) {
        return new ProcessWebhookEventsUsecase(events, gateway, handlers, state, clock);
    }

    @Bean
    public ReconcileStuckIntentsUsecase reconcileStuckIntentsUsecase(PaymentGateway gateway, ProviderRegistry providers,
                                                                     PaymentStateService state, Clock clock) {
        return new ReconcileStuckIntentsUsecase(gateway, providers, state, clock);
    }

    // =========================================================================
    // Services
    // =========================================================================

    @Bean
    public PaymentStateService paymentStateService(PaymentGateway gateway, OutboxWriter outbox, Clock clock) {
        return new PaymentStateService(gateway, outbox, clock);
    }

    @Bean
    public OutboxWriter outboxWriter(PaymentGateway gateway, JsonMapper mapper, Clock clock) {
        return new OutboxWriter(gateway, mapper, clock);
    }

    // =========================================================================
    // Gateways
    // =========================================================================

    @Bean
    public PaymentGateway paymentGateway(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcPaymentGateway(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    public WebhookEventGateway webhookEventGateway(JdbcTemplate jdbcTemplate) {
        return new JdbcWebhookEventGateway(jdbcTemplate);
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
