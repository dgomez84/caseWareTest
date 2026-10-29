package com.caseware.fanout.simulation;

import com.caseware.fanout.exception.DownstreamThrottledException;
import com.caseware.fanout.exception.NonRetryableDownstreamException;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.FanOutBatchReport;
import com.caseware.fanout.model.TemplatePublishedEvent;
import com.caseware.fanout.model.TemplateVersionState;
import com.caseware.fanout.port.InMemoryFanOutIdempotencyStore;
import com.caseware.fanout.resilience.DownstreamCapacityGovernor;
import com.caseware.fanout.resilience.RetryPolicyConfig;
import com.caseware.fanout.worker.TemplatePublishFanOutWorker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Optional interactive simulation runner allowing evaluators to observe the
 * {@link TemplatePublishFanOutWorker} in action from the command line via:
 * <pre>
 *   ./gradlew bootRun --args='--caseware.fanout.simulation.enabled=true'
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "caseware.fanout.simulation.enabled", havingValue = "true", matchIfMissing = true)
public class FanOutSimulationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FanOutSimulationRunner.class);

    @Override
    public void run(String... args) {
        log.info("================================================================================");
        log.info(" CASEWARE TEMPLATE-PUBLISH FAN-OUT WORKER — INTERACTIVE SIMULATION");
        log.info("================================================================================");

        List<EngagementRef> engagements = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            engagements.add(new EngagementRef("FIRM-LARGE-01", "ENG-LARGE-" + i, "TPL-AUDIT-CORE", "CA-IFRS", "v6"));
        }
        engagements.add(new EngagementRef("FIRM-SMALL-A", "ENG-SMALL-A1", "TPL-AUDIT-CORE", "CA-IFRS", "v6"));
        engagements.add(new EngagementRef("FIRM-SMALL-B", "ENG-SMALL-B1", "TPL-AUDIT-CORE", "CA-IFRS", "v6"));
        engagements.add(new EngagementRef("FIRM-SMALL-C", "ENG-CORRUPT-422", "TPL-AUDIT-CORE", "CA-IFRS", "v6"));

        Set<String> throttledOnce = ConcurrentHashMap.newKeySet();
        DownstreamCapacityGovernor governor = new DownstreamCapacityGovernor(4);
        InMemoryFanOutIdempotencyStore idempotencyStore = new InMemoryFanOutIdempotencyStore();
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        TemplatePublishFanOutWorker simulationWorker = new TemplatePublishFanOutWorker(
                (tpl, branch, ver) -> engagements,
                (eng, evt) -> {
                    if ("ENG-CORRUPT-422".equals(eng.engagementId())) {
                        throw new NonRetryableDownstreamException("422 Unprocessable Entity: corrupt workpaper metadata");
                    }
                    if ("ENG-LARGE-3".equals(eng.engagementId()) && throttledOnce.add(eng.engagementId())) {
                        log.warn("[Downstream] Simulating HTTP 429 Too Many Requests on {}", eng.engagementId());
                        throw new DownstreamThrottledException("HTTP 429 Downstream Capacity Limit", Duration.ofMillis(40));
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    log.info("[Downstream] Updated engagement={} (firm={}) to version={}",
                            eng.engagementId(), eng.firmId(), evt.targetVersion());
                },
                idempotencyStore,
                (tpl, branch, ver) -> TemplateVersionState.PUBLISHED,
                (eng, evt, cause, attempts) -> log.error("[DLQ] Routed engagement={} (firm={}) to DLQ after {} attempt(s): {}",
                        eng.engagementId(), eng.firmId(), attempts, cause.getMessage()),
                governor,
                new RetryPolicyConfig(3, Duration.ofMillis(30), Duration.ofMillis(100), 2.0, true),
                meterRegistry
        );

        TemplatePublishedEvent event = new TemplatePublishedEvent(
                "EVT-SIM-2026-001",
                "TPL-AUDIT-CORE",
                "CA-IFRS",
                "v7",
                "s3://caseware-worm/summaries/TPL-AUDIT-CORE/v6_to_v7.json",
                Instant.now()
        );

        log.info("--- Step 1: Processing initial TemplatePublishedEvent ({} candidate engagements, maxConcurrency=4) ---",
                engagements.size());
        FanOutBatchReport firstReport = simulationWorker.onTemplatePublished(event);
        log.info("First Delivery Report : {}", firstReport);
        log.info("Peak Observed InFlight: {} (Max Allowed: {})", governor.peakObservedInFlight(), governor.maxConcurrency());

        log.info("--- Step 2: Simulating duplicate SQS event redelivery (verifying idempotency) ---");
        FanOutBatchReport duplicateReport = simulationWorker.onTemplatePublished(event);
        log.info("Second Delivery Report: {}", duplicateReport);
        log.info("Micrometer Metrics    : completed={}, skippedIdempotent={}, failedDlq={}, throttled429={}",
                meterRegistry.get(TemplatePublishFanOutWorker.METRIC_COMPLETED).counter().count(),
                meterRegistry.get(TemplatePublishFanOutWorker.METRIC_SKIPPED_IDEMPOTENT).counter().count(),
                meterRegistry.get(TemplatePublishFanOutWorker.METRIC_FAILED_DLQ).counter().count(),
                meterRegistry.get(TemplatePublishFanOutWorker.METRIC_DOWNSTREAM_THROTTLED).counter().count());
        log.info("================================================================================");
    }
}
