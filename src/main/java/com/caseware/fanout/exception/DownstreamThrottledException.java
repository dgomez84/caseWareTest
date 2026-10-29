package com.caseware.fanout.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * Thrown when the downstream Engagement Management System signals capacity exhaustion
 * (e.g. HTTP 429 Too Many Requests / Retry-After). Triggers a global adaptive cooldown pause.
 */
public class DownstreamThrottledException extends RetryableDownstreamException {

    private final Duration retryAfter;

    public DownstreamThrottledException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter must not be null");
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
