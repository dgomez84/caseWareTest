package com.caseware.fanout.worker;

import com.caseware.fanout.config.FanOutWorkerProperties;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.FanOutBatchReport;
import com.caseware.fanout.model.TemplatePublishedEvent;
import com.caseware.fanout.port.EngagementLookupPort;
import com.caseware.fanout.resilience.DownstreamCapacityGovernor;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "caseware.fanout.simulation.enabled=false",
        "caseware.fanout.max-concurrency=8",
        "caseware.fanout.retry.max-attempts=3",
        "caseware.fanout.retry.initial-delay=10ms"
})
class TemplatePublishFanOutWorkerSpringTest {

    @Autowired
    private TemplatePublishFanOutWorker worker;

    @Autowired
    private DownstreamCapacityGovernor capacityGovernor;

    @Autowired
    private FanOutWorkerProperties properties;

    @Autowired
    private MeterRegistry meterRegistry;

    @TestConfiguration
    static class TestOverrideConfig {
        @Bean
        @Primary
        public EngagementLookupPort testEngagementLookupPort() {
            return (templateId, marketBranch, targetVersion) -> List.of(
                    new EngagementRef("FIRM-CA-1", "ENG-101", templateId, marketBranch, "v6"),
                    new EngagementRef("FIRM-CA-2", "ENG-202", templateId, marketBranch, "v6")
            );
        }
    }

    @Test
    @DisplayName("Spring Boot 4 Context: Binds configuration properties, records Micrometer metrics, and executes fan-out")
    void shouldLoadSpringBoot4ContextAndExecuteFanOut() {
        assertThat(properties.maxConcurrency()).isEqualTo(8);
        assertThat(capacityGovernor.maxConcurrency()).isEqualTo(8);

        TemplatePublishedEvent event = new TemplatePublishedEvent(
                "EVT-SPRING-001",
                "TPL-AUDIT-CA",
                "CA-IFRS",
                "v7",
                "s3://caseware-worm/summaries/v6_to_v7.json",
                Instant.parse("2026-10-29T14:00:00Z")
        );

        FanOutBatchReport report = worker.onTemplatePublished(event);

        assertThat(report.totalDiscovered()).isEqualTo(2);
        assertThat(report.completedCount()).isEqualTo(2);
        assertThat(report.failedCount()).isZero();
        assertThat(meterRegistry.get(TemplatePublishFanOutWorker.METRIC_COMPLETED).counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.get(TemplatePublishFanOutWorker.METRIC_DOWNSTREAM_INFLIGHT).gauge().value()).isEqualTo(0.0);
    }
}
