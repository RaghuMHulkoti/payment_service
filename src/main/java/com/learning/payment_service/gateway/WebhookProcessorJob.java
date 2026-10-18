package com.learning.payment_service.gateway;

import com.learning.payment_service.core.usecase.ProcessWebhookEventsUsecase;
import com.learning.payment_service.core.usecase.ProcessWebhookEventsUsecaseRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Applies stored webhook events shortly after the endpoint accepted them (TDD §14.4: heavy work is async). */
public class WebhookProcessorJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessorJob.class);
    private static final int BATCH = 50;

    private final ProcessWebhookEventsUsecase usecase;

    public WebhookProcessorJob(ProcessWebhookEventsUsecase usecase) {
        this.usecase = usecase;
    }

    @Scheduled(fixedDelayString = "${payment.jobs.webhook-poll-interval:5s}")
    public void run() {
        try {
            int processed = usecase.execute(new ProcessWebhookEventsUsecaseRequest(BATCH)).getProcessed();
            if (processed > 0) {
                log.info("Applied {} webhook event(s)", processed);
            }
        } catch (RuntimeException e) {
            log.warn("Webhook processing failed: {}", e.getMessage());
        }
    }
}
