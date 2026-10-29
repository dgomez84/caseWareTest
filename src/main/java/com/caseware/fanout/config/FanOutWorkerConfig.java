package com.caseware.fanout.config;

import com.caseware.fanout.model.TemplateVersionState;
import com.caseware.fanout.port.DeadLetterPort;
import com.caseware.fanout.port.DownstreamEngagementProcessorPort;
import com.caseware.fanout.port.EngagementLookupPort;
import com.caseware.fanout.port.FanOutIdempotencyStorePort;
import com.caseware.fanout.port.InMemoryFanOutIdempotencyStore;
import com.caseware.fanout.port.TemplateVersionLifecyclePort;
import com.caseware.fanout.resilience.DownstreamCapacityGovernor;
import com.caseware.fanout.resilience.RetryPolicyConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring Boot 4 configuration wiring the resilience policies, Micrometer observability registry,
 * and default port adapters for {@link com.caseware.fanout.worker.TemplatePublishFanOutWorker}.
 */
@Configuration
public class FanOutWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(FanOutWorkerConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public DownstreamCapacityGovernor downstreamCapacityGovernor(FanOutWorkerProperties properties) {
        return new DownstreamCapacityGovernor(properties.maxConcurrency());
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicyConfig retryPolicyConfig(FanOutWorkerProperties properties) {
        FanOutWorkerProperties.RetryProperties r = properties.retry();
        return new RetryPolicyConfig(
                r.maxAttempts(),
                r.initialDelay(),
                r.maxDelay(),
                r.multiplier(),
                r.jitterEnabled()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public FanOutIdempotencyStorePort fanOutIdempotencyStorePort() {
        return new InMemoryFanOutIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public EngagementLookupPort defaultEngagementLookupPort() {
        return (templateId, marketBranch, targetVersion) -> List.of();
    }

    @Bean
    @ConditionalOnMissingBean
    public DownstreamEngagementProcessorPort defaultDownstreamEngagementProcessorPort() {
        return (engagement, event) -> log.debug(
                "Processed engagement={} for template={} version={}",
                engagement.engagementId(), event.templateId(), event.targetVersion()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateVersionLifecyclePort defaultTemplateVersionLifecyclePort() {
        return (templateId, marketBranch, version) -> TemplateVersionState.PUBLISHED;
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterPort defaultDeadLetterPort() {
        return (engagement, event, cause, attemptsMade) -> log.error(
                "Routed engagement={} to DLQ after {} attempts: {}",
                engagement.engagementId(), attemptsMade, cause.getMessage()
        );
    }
}
