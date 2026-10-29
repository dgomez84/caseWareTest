package com.caseware.fanout.worker;

import com.caseware.fanout.exception.DownstreamThrottledException;
import com.caseware.fanout.exception.NonRetryableDownstreamException;
import com.caseware.fanout.exception.RetryableDownstreamException;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.FanOutBatchReport;
import com.caseware.fanout.model.ProcessingStatus;
import com.caseware.fanout.model.TemplatePublishedEvent;
import com.caseware.fanout.model.TemplateVersionState;
import com.caseware.fanout.port.DeadLetterPort;
import com.caseware.fanout.port.DownstreamEngagementProcessorPort;
import com.caseware.fanout.port.EngagementLookupPort;
import com.caseware.fanout.port.FanOutIdempotencyStorePort;
import com.caseware.fanout.port.InMemoryFanOutIdempotencyStore;
import com.caseware.fanout.port.TemplateVersionLifecyclePort;
import com.caseware.fanout.resilience.DownstreamCapacityGovernor;
import com.caseware.fanout.resilience.RetryPolicyConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TemplatePublishFanOutWorkerTest {

    private static final TemplatePublishedEvent SAMPLE_EVENT = new TemplatePublishedEvent(
            "EVT-2026-001",
            "TPL-AUDIT-CORE",
            "CA-IFRS",
            "v7",
            "s3://caseware-summaries-worm/TPL-AUDIT-CORE/v4_to_v7.json",
            Instant.parse("2026-10-29T12:00:00Z")
    );

    @Test
    @DisplayName("1. Concurrency Cap: Strictly respects downstream maxConcurrency across a large batch on Virtual Threads")
    void shouldStrictlyRespectDownstreamConcurrencyLimitAcrossLargeBatch() {
        int totalEngagements = 120;
        int maxConcurrency = 6;

        List<EngagementRef> engagements = IntStream.rangeClosed(1, totalEngagements)
                .mapToObj(i -> new EngagementRef("FIRM-" + (i % 10), "ENG-" + i, "TPL-AUDIT-CORE", "CA-IFRS", "v4"))
                .toList();

        AtomicInteger downstreamInFlight = new AtomicInteger(0);
        AtomicInteger maxObservedDownstreamInFlight = new AtomicInteger(0);

        DownstreamEngagementProcessorPort downstreamPort = (engagement, event) -> {
            int current = downstreamInFlight.incrementAndGet();
            maxObservedDownstreamInFlight.accumulateAndGet(current, Math::max);
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                downstreamInFlight.decrementAndGet();
            }
        };

        DownstreamCapacityGovernor governor = new DownstreamCapacityGovernor(maxConcurrency);
        TemplatePublishFanOutWorker worker = new TemplatePublishFanOutWorker(
                (tpl, branch, ver) -> engagements,
                downstreamPort,
                new InMemoryFanOutIdempotencyStore(),
                (tpl, branch, ver) -> TemplateVersionState.PUBLISHED,
                (eng, evt, cause, attempts) -> {},
                governor,
                new RetryPolicyConfig(2, Duration.ofMillis(10), Duration.ofMillis(50), 2.0, false)
        );

        FanOutBatchReport report = worker.onTemplatePublished(SAMPLE_EVENT);

        assertThat(report.totalDiscovered()).isEqualTo(totalEngagements);
        assertThat(report.completedCount()).isEqualTo(totalEngagements);
        assertThat(report.failedCount()).isZero();
        assertThat(maxObservedDownstreamInFlight.get()).isLessThanOrEqualTo(maxConcurrency);
        assertThat(governor.peakObservedInFlight()).isLessThanOrEqualTo(maxConcurrency);
        assertThat(governor.currentInFlight()).isZero();
    }

    @Test
    @DisplayName("2. Idempotency: Duplicate TemplatePublishedEvent redelivery skips already completed engagements")
    void shouldEnforceIdempotencyOnDuplicateEventRedelivery() {
        List<EngagementRef> engagements = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> new EngagementRef("FIRM-1", "ENG-" + i, "TPL-AUDIT-CORE", "CA-IFRS", "v4"))
                .toList();

        AtomicInteger downstreamCallCount = new AtomicInteger(0);
        FanOutIdempotencyStorePort idempotencyStore = new InMemoryFanOutIdempotencyStore();

        TemplatePublishFanOutWorker worker = new TemplatePublishFanOutWorker(
                (tpl, branch, ver) -> engagements,
                (eng, evt) -> downstreamCallCount.incrementAndGet(),
                idempotencyStore,
                (tpl, branch, ver) -> TemplateVersionState.PUBLISHED,
                (eng, evt, cause, attempts) -> {},
                new DownstreamCapacityGovernor(10),
                RetryPolicyConfig.defaults()
        );

        // First delivery of the event
        FanOutBatchReport firstReport = worker.onTemplatePublished(SAMPLE_EVENT);
        assertThat(firstReport.completedCount()).isEqualTo(30);
        assertThat(firstReport.skippedIdempotentCount()).isZero();
        assertThat(downstreamCallCount.get()).isEqualTo(30);

        // Second (duplicate) delivery of the exact same event from SQS
        FanOutBatchReport secondReport = worker.onTemplatePublished(SAMPLE_EVENT);
        assertThat(secondReport.completedCount()).isZero();
        assertThat(secondReport.skippedIdempotentCount()).isEqualTo(30);
        // Zero additional calls made to the expensive 1-minute downstream service
        assertThat(downstreamCallCount.get()).isEqualTo(30);
    }

    @Test
    @DisplayName("3. Semaphore-Safe Retries & 429 Throttling: Releases permit before backoff sleep so other engagements can proceed")
    void shouldReleaseSemaphorePermitBeforeSleepingOnRetryAndRecoverFromTransientAndThrottleErrors() {
        EngagementRef eng1 = new EngagementRef("FIRM-A", "ENG-1", "TPL-AUDIT-CORE", "CA-IFRS", "v4");
        EngagementRef eng2 = new EngagementRef("FIRM-B", "ENG-2", "TPL-AUDIT-CORE", "CA-IFRS", "v4");

        Map<String, AtomicInteger> attemptsByEngagement = new ConcurrentHashMap<>();
        List<String> completionOrder = new CopyOnWriteArrayList<>();

        DownstreamEngagementProcessorPort downstreamPort = (engagement, event) -> {
            int attempt = attemptsByEngagement
                    .computeIfAbsent(engagement.engagementId(), k -> new AtomicInteger(0))
                    .incrementAndGet();

            if ("ENG-1".equals(engagement.engagementId()) && attempt == 1) {
                throw new RetryableDownstreamException("Transient downstream socket timeout on attempt 1");
            }
            if ("ENG-1".equals(engagement.engagementId()) && attempt == 2) {
                throw new DownstreamThrottledException("HTTP 429 Too Many Requests", Duration.ofMillis(25));
            }
            completionOrder.add(engagement.engagementId() + "#attempt" + attempt);
        };

        // Single permit (maxConcurrency = 1)! If ENG-1 held the permit while sleeping 120ms on backoff,
        // ENG-2 would not be able to complete before ENG-1's retry.
        DownstreamCapacityGovernor singlePermitGovernor = new DownstreamCapacityGovernor(1);
        RetryPolicyConfig retryPolicy = new RetryPolicyConfig(
                3,
                Duration.ofMillis(120),
                Duration.ofMillis(200),
                1.0,
                false
        );

        TemplatePublishFanOutWorker worker = new TemplatePublishFanOutWorker(
                (tpl, branch, ver) -> List.of(eng1, eng2),
                downstreamPort,
                new InMemoryFanOutIdempotencyStore(),
                (tpl, branch, ver) -> TemplateVersionState.PUBLISHED,
                (eng, evt, cause, attempts) -> {},
                singlePermitGovernor,
                retryPolicy
        );

        FanOutBatchReport report = worker.onTemplatePublished(SAMPLE_EVENT);

        assertThat(report.completedCount()).isEqualTo(2);
        assertThat(report.failedCount()).isZero();
        assertThat(attemptsByEngagement.get("ENG-1").get()).isEqualTo(3);
        assertThat(attemptsByEngagement.get("ENG-2").get()).isEqualTo(1);
        // ENG-2 completed while ENG-1 was sleeping outside the semaphore on its first backoff
        assertThat(completionOrder).containsExactly("ENG-2#attempt1", "ENG-1#attempt3");
    }

    @Test
    @DisplayName("4. Failure Isolation & DLQ: Non-retryable errors fail fast (1 attempt), exhausted retries route to DLQ, healthy engagements succeed")
    void shouldIsolatePermanentFailuresAndExhaustedRetriesToDeadLetterWithoutFailingBatch() {
        EngagementRef healthy1 = new EngagementRef("FIRM-1", "ENG-OK-1", "TPL-AUDIT-CORE", "CA-IFRS", "v4");
        EngagementRef corruptPermanent = new EngagementRef("FIRM-2", "ENG-CORRUPT-400", "TPL-AUDIT-CORE", "CA-IFRS", "v4");
        EngagementRef persistentTimeout = new EngagementRef("FIRM-3", "ENG-TIMEOUT-503", "TPL-AUDIT-CORE", "CA-IFRS", "v4");
        EngagementRef healthy2 = new EngagementRef("FIRM-4", "ENG-OK-2", "TPL-AUDIT-CORE", "CA-IFRS", "v4");

        Map<String, AtomicInteger> attemptsByEngagement = new ConcurrentHashMap<>();
        List<String> deadLetteredIds = new CopyOnWriteArrayList<>();
        Map<String, Integer> deadLetterAttempts = new ConcurrentHashMap<>();

        DownstreamEngagementProcessorPort downstreamPort = (engagement, event) -> {
            attemptsByEngagement
                    .computeIfAbsent(engagement.engagementId(), k -> new AtomicInteger(0))
                    .incrementAndGet();

            if ("ENG-CORRUPT-400".equals(engagement.engagementId())) {
                throw new NonRetryableDownstreamException("422 Unprocessable Entity: corrupt legacy engagement state");
            }
            if ("ENG-TIMEOUT-503".equals(engagement.engagementId())) {
                throw new RetryableDownstreamException("503 Service Unavailable");
            }
        };

        DeadLetterPort deadLetterPort = (eng, evt, cause, attempts) -> {
            deadLetteredIds.add(eng.engagementId());
            deadLetterAttempts.put(eng.engagementId(), attempts);
        };

        FanOutIdempotencyStorePort idempotencyStore = new InMemoryFanOutIdempotencyStore();
        TemplatePublishFanOutWorker worker = new TemplatePublishFanOutWorker(
                (tpl, branch, ver) -> List.of(healthy1, corruptPermanent, persistentTimeout, healthy2),
                downstreamPort,
                idempotencyStore,
                (tpl, branch, ver) -> TemplateVersionState.PUBLISHED,
                deadLetterPort,
                new DownstreamCapacityGovernor(4),
                new RetryPolicyConfig(3, Duration.ofMillis(10), Duration.ofMillis(20), 1.5, false)
        );

        FanOutBatchReport report = worker.onTemplatePublished(SAMPLE_EVENT);

        assertThat(report.totalDiscovered()).isEqualTo(4);
        assertThat(report.completedCount()).isEqualTo(2);
        assertThat(report.failedCount()).isEqualTo(2);

        // Non-retryable exception must NOT waste downstream capacity on retries (exactly 1 attempt)
        assertThat(attemptsByEngagement.get("ENG-CORRUPT-400").get()).isEqualTo(1);
        assertThat(deadLetterAttempts.get("ENG-CORRUPT-400")).isEqualTo(1);

        // Retryable exception must exhaust all 3 configured attempts before routing to DLQ
        assertThat(attemptsByEngagement.get("ENG-TIMEOUT-503").get()).isEqualTo(3);
        assertThat(deadLetterAttempts.get("ENG-TIMEOUT-503")).isEqualTo(3);

        assertThat(deadLetteredIds).containsExactlyInAnyOrder("ENG-CORRUPT-400", "ENG-TIMEOUT-503");
    }

    @Test
    @DisplayName("5. Multi-Tenant Fairness: Prevents a large firm from starving smaller firms (Round-Robin interleaving)")
    void shouldInterleaveEngagementsFairlyAcrossSkewedTenantsPreventingHeadOfLineBlocking() {
        List<EngagementRef> skewedInput = new ArrayList<>();
        // Large firm with 50 engagements listed first (simulating the 40,000-engagement firm skew)
        for (int i = 1; i <= 50; i++) {
            skewedInput.add(new EngagementRef("FIRM-LARGE", "LARGE-" + i, "TPL-AUDIT-CORE", "CA-IFRS", "v4"));
        }
        // Three small firms with only 1 engagement each at the very end of the query result
        skewedInput.add(new EngagementRef("FIRM-SMALL-A", "SMALL-A-1", "TPL-AUDIT-CORE", "CA-IFRS", "v4"));
        skewedInput.add(new EngagementRef("FIRM-SMALL-B", "SMALL-B-1", "TPL-AUDIT-CORE", "CA-IFRS", "v4"));
        skewedInput.add(new EngagementRef("FIRM-SMALL-C", "SMALL-C-1", "TPL-AUDIT-CORE", "CA-IFRS", "v4"));

        List<String> executionOrderByFirm = Collections.synchronizedList(new ArrayList<>());

        // Use maxConcurrency = 1 to observe deterministic dispatch order
        TemplatePublishFanOutWorker worker = new TemplatePublishFanOutWorker(
                (tpl, branch, ver) -> skewedInput,
                (eng, evt) -> executionOrderByFirm.add(eng.firmId()),
                new InMemoryFanOutIdempotencyStore(),
                (tpl, branch, ver) -> TemplateVersionState.PUBLISHED,
                (eng, evt, cause, attempts) -> {},
                new DownstreamCapacityGovernor(1),
                RetryPolicyConfig.defaults()
        );

        FanOutBatchReport report = worker.onTemplatePublished(SAMPLE_EVENT);

        assertThat(report.completedCount()).isEqualTo(53);
        // The very first 4 calls must serve 1 engagement from each of the 4 distinct firms!
        assertThat(executionOrderByFirm.subList(0, 4))
                .containsExactly("FIRM-LARGE", "FIRM-SMALL-A", "FIRM-SMALL-B", "FIRM-SMALL-C");
    }

    @Test
    @DisplayName("6. Version Withdrawal Mid-Flight: Immediately skips queued engagements when version is marked WITHDRAWN")
    void shouldAbortRemainingEngagementsImmediatelyWhenTemplateVersionIsWithdrawnMidFlight() {
        List<EngagementRef> engagements = IntStream.rangeClosed(1, 40)
                .mapToObj(i -> new EngagementRef("FIRM-" + i, "ENG-" + i, "TPL-AUDIT-CORE", "CA-IFRS", "v4"))
                .toList();

        AtomicReference<TemplateVersionState> liveVersionState = new AtomicReference<>(TemplateVersionState.PUBLISHED);
        AtomicInteger processedCount = new AtomicInteger(0);

        DownstreamEngagementProcessorPort downstreamPort = (engagement, event) -> {
            int done = processedCount.incrementAndGet();
            if (done >= 5) {
                // Content team withdraws v7 mid-flight!
                liveVersionState.set(TemplateVersionState.WITHDRAWN);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        FanOutIdempotencyStorePort idempotencyStore = new InMemoryFanOutIdempotencyStore();
        TemplatePublishFanOutWorker worker = new TemplatePublishFanOutWorker(
                (tpl, branch, ver) -> engagements,
                downstreamPort,
                idempotencyStore,
                (tpl, branch, ver) -> liveVersionState.get(),
                (eng, evt, cause, attempts) -> {},
                new DownstreamCapacityGovernor(2),
                RetryPolicyConfig.defaults()
        );

        FanOutBatchReport report = worker.onTemplatePublished(SAMPLE_EVENT);

        assertThat(report.totalDiscovered()).isEqualTo(40);
        assertThat(report.completedCount()).isBetween(5, 8);
        assertThat(report.skippedObsoleteCount()).isEqualTo(40 - report.completedCount());
        assertThat(report.failedCount()).isZero();
    }
}
