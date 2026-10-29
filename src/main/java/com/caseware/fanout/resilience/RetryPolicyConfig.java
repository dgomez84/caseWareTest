package com.caseware.fanout.resilience;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configuration for bounded retries with Exponential Backoff and Full Jitter.
 *
 * @param maxAttempts   maximum total execution attempts (must be >= 1)
 * @param initialDelay  base backoff delay after the first failure
 * @param maxDelay      upper bound on any computed backoff delay
 * @param multiplier    exponential multiplier per retry attempt
 * @param jitterEnabled whether to apply random jitter in [0.5 * delay, 1.0 * delay] to prevent retry storms
 */
public record RetryPolicyConfig(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double multiplier,
        boolean jitterEnabled
) {
    public RetryPolicyConfig {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
    }

    public static RetryPolicyConfig defaults() {
        return new RetryPolicyConfig(
                3,
                Duration.ofSeconds(2),
                Duration.ofSeconds(30),
                2.0,
                true
        );
    }

    /**
     * Computes the backoff duration after the given failed attempt number (1-indexed).
     */
    public Duration computeDelayForFailedAttempt(int failedAttemptNumber) {
        double rawMillis = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, failedAttemptNumber - 1));
        long cappedMillis = (long) Math.min(rawMillis, maxDelay.toMillis());
        if (!jitterEnabled || cappedMillis <= 1) {
            return Duration.ofMillis(Math.max(0, cappedMillis));
        }
        long half = Math.max(1, cappedMillis / 2);
        long jittered = half + ThreadLocalRandom.current().nextLong(cappedMillis - half + 1);
        return Duration.ofMillis(jittered);
    }
}
