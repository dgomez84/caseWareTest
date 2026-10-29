package com.caseware.fanout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Externalized Spring Boot 4 configuration properties for the Template-Publish Fan-Out Worker.
 */
@ConfigurationProperties(prefix = "caseware.fanout")
public record FanOutWorkerProperties(
        @DefaultValue("20") int maxConcurrency,
        @DefaultValue RetryProperties retry
) {
    public record RetryProperties(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("2s") Duration initialDelay,
            @DefaultValue("30s") Duration maxDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("true") boolean jitterEnabled
    ) {
    }
}
