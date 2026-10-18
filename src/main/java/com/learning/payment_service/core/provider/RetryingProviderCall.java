package com.learning.payment_service.core.provider;

import java.time.Duration;
import java.util.function.Supplier;

/** Runs a PSP call with exponential backoff on {@link ProviderTransientException} only (TDD §14.6). */
public class RetryingProviderCall {

    private final int maxAttempts;
    private final Duration backoff;

    public RetryingProviderCall(int maxAttempts, Duration backoff) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff;
    }

    public <T> T call(Supplier<T> call) {
        ProviderTransientException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (ProviderTransientException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleep(backoff.multipliedBy(1L << (attempt - 1)));
                }
            }
        }
        throw last;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderTransientException("Interrupted while retrying the payment provider", e);
        }
    }
}
