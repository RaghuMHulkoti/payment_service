package com.learning.payment_service.gateway;

import com.learning.payment_service.config.PaymentProperties;
import com.learning.payment_service.core.usecase.ReconcileStuckIntentsUsecase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Compares stuck intents with the PSP's canonical status (TDD §8.8, §14.6). */
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final int BATCH = 100;

    private final ReconcileStuckIntentsUsecase usecase;
    private final PaymentProperties properties;

    public ReconciliationJob(ReconcileStuckIntentsUsecase usecase, PaymentProperties properties) {
        this.usecase = usecase;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${payment.jobs.reconcile-interval:15m}", initialDelayString = "${payment.jobs.reconcile-interval:15m}")
    public void run() {
        try {
            int reconciled = usecase.execute(
                    new ReconcileStuckIntentsUsecase.Request(properties.stuckAfter(), BATCH)).reconciled();
            if (reconciled > 0) {
                log.info("Reconciled {} stuck payment(s)", reconciled);
            }
        } catch (RuntimeException e) {
            log.warn("Payment reconciliation failed: {}", e.getMessage());
        }
    }
}
