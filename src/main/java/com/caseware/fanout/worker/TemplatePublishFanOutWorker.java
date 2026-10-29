package com.caseware.fanout.worker;

import com.caseware.fanout.exception.DownstreamProcessingException;
import com.caseware.fanout.exception.DownstreamThrottledException;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.FanOutBatchReport;
import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.ProcessingStatus;
import com.caseware.fanout.model.TemplatePublishedEvent;
import com.caseware.fanout.model.TemplateVersionState;
import com.caseware.fanout.port.DeadLetterPort;
import com.caseware.fanout.port.DownstreamEngagementProcessorPort;
import com.caseware.fanout.port.EngagementLookupPort;
import com.caseware.fanout.port.FanOutIdempotencyStorePort;
import com.caseware.fanout.port.TemplateVersionLifecyclePort;
import com.caseware.fanout.resilience.DownstreamCapacityGovernor;
import com.caseware.fanout.resilience.FairTenantScheduler;
import com.caseware.fanout.resilience.RetryPolicyConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Part 2 Targeted Implementation: Template-Publish Fan-Out Worker.
 * <p>
 * Coordinates the fan-out of a {@link TemplatePublishedEvent} across potentially tens of thousands
 * of engagements while invoking a capacity-constrained downstream dependency (~1 minute per call).
 *
 * <h3>Key Staff-Level Design Guarantees:</h3>
 * <ol>
 *   <li><b>Java 21/25 Virtual Threads:</b> Handles thousands of long-blocking (~1 min) I/O calls concurrently
 *       without exhausting OS platform threads.</li>
 *   <li><b>Strict Downstream Capacity Protection:</b> Enforces a hard concurrency ceiling via
 *       {@link DownstreamCapacityGovernor} and applies global adaptive cooldowns on HTTP 429 signals.</li>
 *   <li><b>Semaphore-Safe Retries:</b> Always releases the concurrency permit <i>before</i> sleeping
 *       during exponential backoff so throttled/retrying tasks never starve healthy tasks.</li>
 *   <li><b>Multi-Tenant Fairness (Skew Mitigation):</b> Uses {@link FairTenantScheduler} Round-Robin
 *       interleaving so a 40,000-engagement firm cannot cause Head-of-Line blocking for 40-engagement firms.</li>
 *   <li><b>Atomic Idempotency & Pre-Flight Withdrawal Guard:</b> Ensures at-most-once execution per
 *       {@code (templateId, targetVersion, engagementId)} and aborts pending calls immediately if the
 *       published version is withdrawn or superseded mid-flight.</li>
 *   <li><b>First-Class Observability (Micrometer):</b> Emits real-time counters and gauges for completed,
 *       idempotent-skipped, obsolete-skipped, dead-lettered, and throttled downstream calls.</li>
 * </ol>
 */
@Service
public class TemplatePublishFanOutWorker {

    public static final String METRIC_COMPLETED = "caseware.fanout.engagements.completed";
    public static final String METRIC_SKIPPED_IDEMPOTENT = "caseware.fanout.engagements.skipped.idempotent";
    public static final String METRIC_SKIPPED_OBSOLETE = "caseware.fanout.engagements.skipped.obsolete";
    public static final String METRIC_FAILED_DLQ = "caseware.fanout.engagements.failed.dlq";
    public static final String METRIC_DOWNSTREAM_THROTTLED = "caseware.fanout.downstream.throttled";
    public static final String METRIC_DOWNSTREAM_INFLIGHT = "caseware.fanout.downstream.inflight";

    private final EngagementLookupPort engagementLookupPort;
    private final DownstreamEngagementProcessorPort downstreamProcessorPort;
    private final FanOutIdempotencyStorePort idempotencyStorePort;
    private final TemplateVersionLifecyclePort versionLifecyclePort;
    private final DeadLetterPort deadLetterPort;
    private final DownstreamCapacityGovernor capacityGovernor;
    private final RetryPolicyConfig retryPolicy;
    private final Counter completedCounter;
    private final Counter skippedIdempotentCounter;
    private final Counter skippedObsoleteCounter;
    private final Counter failedDlqCounter;
    private final Counter downstreamThrottledCounter;

    public TemplatePublishFanOutWorker(
            EngagementLookupPort engagementLookupPort,
            DownstreamEngagementProcessorPort downstreamProcessorPort,
            FanOutIdempotencyStorePort idempotencyStorePort,
            TemplateVersionLifecyclePort versionLifecyclePort,
            DeadLetterPort deadLetterPort,
            DownstreamCapacityGovernor capacityGovernor,
            RetryPolicyConfig retryPolicy
    ) {
        this(
                engagementLookupPort,
                downstreamProcessorPort,
                idempotencyStorePort,
                versionLifecyclePort,
                deadLetterPort,
                capacityGovernor,
                retryPolicy,
                new SimpleMeterRegistry()
        );
    }

    @Autowired
    public TemplatePublishFanOutWorker(
            EngagementLookupPort engagementLookupPort,
            DownstreamEngagementProcessorPort downstreamProcessorPort,
            FanOutIdempotencyStorePort idempotencyStorePort,
            TemplateVersionLifecyclePort versionLifecyclePort,
            DeadLetterPort deadLetterPort,
            DownstreamCapacityGovernor capacityGovernor,
            RetryPolicyConfig retryPolicy,
            MeterRegistry meterRegistry
    ) {
        this.engagementLookupPort = Objects.requireNonNull(engagementLookupPort, "engagementLookupPort");
        this.downstreamProcessorPort = Objects.requireNonNull(downstreamProcessorPort, "downstreamProcessorPort");
        this.idempotencyStorePort = Objects.requireNonNull(idempotencyStorePort, "idempotencyStorePort");
        this.versionLifecyclePort = Objects.requireNonNull(versionLifecyclePort, "versionLifecyclePort");
        this.deadLetterPort = Objects.requireNonNull(deadLetterPort, "deadLetterPort");
        this.capacityGovernor = Objects.requireNonNull(capacityGovernor, "capacityGovernor");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(meterRegistry, "meterRegistry");

        this.completedCounter = Counter.builder(METRIC_COMPLETED)
                .description("Total engagements successfully processed downstream")
                .register(meterRegistry);
        this.skippedIdempotentCounter = Counter.builder(METRIC_SKIPPED_IDEMPOTENT)
                .description("Engagements skipped due to idempotency claim")
                .register(meterRegistry);
        this.skippedObsoleteCounter = Counter.builder(METRIC_SKIPPED_OBSOLETE)
                .description("Engagements skipped because template version was withdrawn or superseded")
                .register(meterRegistry);
        this.failedDlqCounter = Counter.builder(METRIC_FAILED_DLQ)
                .description("Engagements routed to Dead-Letter sink after permanent failure or retry exhaustion")
                .register(meterRegistry);
        this.downstreamThrottledCounter = Counter.builder(METRIC_DOWNSTREAM_THROTTLED)
                .description("Downstream HTTP 429 throttle signals received")
                .register(meterRegistry);
        Gauge.builder(METRIC_DOWNSTREAM_INFLIGHT, capacityGovernor, DownstreamCapacityGovernor::currentInFlight)
                .description("Current in-flight requests to the downstream engagement system")
                .register(meterRegistry);
    }

    /**
     * Processes a template publication event across all affected engagements in the region.
     */
    public FanOutBatchReport onTemplatePublished(TemplatePublishedEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        List<EngagementRef> discovered = engagementLookupPort.findAffectedEngagements(
                event.templateId(),
                event.marketBranch(),
                event.targetVersion()
        );

        if (discovered.isEmpty()) {
            return new FanOutBatchReport(event.templateId(), event.targetVersion(), 0, 0, 0, 0, 0);
        }

        // 1. Interleave engagements Round-Robin by firmId to prevent the 40,000-engagement firm
        //    from starving 40-engagement firms (Head-of-Line Blocking).
        List<EngagementRef> fairOrdered = FairTenantScheduler.interleaveByFirmRoundRobin(discovered);

        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger skippedIdempotentCount = new AtomicInteger(0);
        AtomicInteger skippedObsoleteCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        // 2. Claim idempotency keys upfront before allocating virtual threads or semaphore permits.
        Queue<ClaimedEngagementTask> fairClaimedQueue = new ConcurrentLinkedQueue<>();
        for (EngagementRef engagement : fairOrdered) {
            IdempotencyKey key = new IdempotencyKey(
                    event.templateId(),
                    event.targetVersion(),
                    engagement.engagementId()
            );
            if (idempotencyStorePort.tryClaim(key)) {
                fairClaimedQueue.offer(new ClaimedEngagementTask(engagement, key));
            } else {
                skippedIdempotentCount.incrementAndGet();
                skippedIdempotentCounter.increment();
            }
        }

        int tasksToDispatch = fairClaimedQueue.size();
        if (tasksToDispatch == 0) {
            return new FanOutBatchReport(
                    event.templateId(),
                    event.targetVersion(),
                    discovered.size(),
                    0,
                    skippedIdempotentCount.get(),
                    0,
                    0
            );
        }

        // 3. Execute claimed tasks on Java 21/25 Virtual Threads while strictly governing downstream concurrency.
        try (ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(tasksToDispatch);
            for (int i = 0; i < tasksToDispatch; i++) {
                futures.add(virtualThreadExecutor.submit(() -> processNextFromFairQueue(
                        event,
                        fairClaimedQueue,
                        completedCount,
                        skippedObsoleteCount,
                        failedCount
                )));
            }

            for (Future<?> future : futures) {
                waitForFuture(future);
            }
        }

        return new FanOutBatchReport(
                event.templateId(),
                event.targetVersion(),
                discovered.size(),
                completedCount.get(),
                skippedIdempotentCount.get(),
                skippedObsoleteCount.get(),
                failedCount.get()
        );
    }

    private void processNextFromFairQueue(
            TemplatePublishedEvent event,
            Queue<ClaimedEngagementTask> fairClaimedQueue,
            AtomicInteger completedCount,
            AtomicInteger skippedObsoleteCount,
            AtomicInteger failedCount
    ) {
        ClaimedEngagementTask claimedTask = null;
        int maxAttempts = retryPolicy.maxAttempts();
        Throwable lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Fast pre-flight check before waiting on the downstream capacity semaphore
            if (!isVersionStillActive(event)) {
                if (claimedTask == null) {
                    claimedTask = fairClaimedQueue.poll();
                }
                if (claimedTask != null) {
                    idempotencyStorePort.markTerminalState(claimedTask.key(), ProcessingStatus.SKIPPED_OBSOLETE_VERSION);
                    skippedObsoleteCount.incrementAndGet();
                    skippedObsoleteCounter.increment();
                }
                return;
            }

            boolean permitAcquired = false;
            try {
                capacityGovernor.acquirePermit();
                permitAcquired = true;

                // Poll from the Round-Robin queue AFTER acquiring the first permit so that
                // the exact Round-Robin tenant order is preserved regardless of thread wakeup scheduling.
                if (claimedTask == null) {
                    claimedTask = fairClaimedQueue.poll();
                    if (claimedTask == null) {
                        return;
                    }
                }

                // Re-verify version state after waiting in the semaphore queue (in case it was withdrawn while queued)
                if (!isVersionStillActive(event)) {
                    idempotencyStorePort.markTerminalState(claimedTask.key(), ProcessingStatus.SKIPPED_OBSOLETE_VERSION);
                    skippedObsoleteCount.incrementAndGet();
                    skippedObsoleteCounter.increment();
                    return;
                }

                downstreamProcessorPort.processEngagementUpdate(claimedTask.engagement(), event);

                idempotencyStorePort.markCompleted(claimedTask.key());
                completedCount.incrementAndGet();
                completedCounter.increment();
                return;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (claimedTask == null) {
                    claimedTask = fairClaimedQueue.poll();
                }
                if (claimedTask != null) {
                    recordPermanentFailure(claimedTask, event, ie, attempt, failedCount);
                }
                return;

            } catch (DownstreamProcessingException dpe) {
                lastFailure = dpe;
                if (dpe instanceof DownstreamThrottledException throttled) {
                    downstreamThrottledCounter.increment();
                    capacityGovernor.registerThrottleSignal(throttled.retryAfter());
                }
                if (!dpe.isRetryable() || attempt >= maxAttempts) {
                    recordPermanentFailure(claimedTask, event, dpe, attempt, failedCount);
                    return;
                }

            } catch (RuntimeException unexpected) {
                lastFailure = unexpected;
                recordPermanentFailure(claimedTask, event, unexpected, attempt, failedCount);
                return;

            } finally {
                // CRITICAL: Always release the concurrency permit BEFORE sleeping for retry backoff
                if (permitAcquired) {
                    capacityGovernor.releasePermit();
                }
            }

            // Sleep outside the semaphore permit so other engagements can use downstream capacity
            Duration backoffDelay = computeBackoffDelay(attempt, lastFailure);
            if (!sleepQuietly(backoffDelay)) {
                recordPermanentFailure(claimedTask, event, lastFailure, attempt, failedCount);
                return;
            }
        }
    }

    private boolean isVersionStillActive(TemplatePublishedEvent event) {
        TemplateVersionState state = versionLifecyclePort.getVersionState(
                event.templateId(),
                event.marketBranch(),
                event.targetVersion()
        );
        return state == TemplateVersionState.PUBLISHED;
    }

    private Duration computeBackoffDelay(int failedAttempt, Throwable lastFailure) {
        Duration policyDelay = retryPolicy.computeDelayForFailedAttempt(failedAttempt);
        if (lastFailure instanceof DownstreamThrottledException throttled) {
            return throttled.retryAfter().compareTo(policyDelay) > 0 ? throttled.retryAfter() : policyDelay;
        }
        return policyDelay;
    }

    private boolean sleepQuietly(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return true;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void recordPermanentFailure(
            ClaimedEngagementTask claimedTask,
            TemplatePublishedEvent event,
            Throwable cause,
            int attemptsMade,
            AtomicInteger failedCount
    ) {
        idempotencyStorePort.markTerminalState(claimedTask.key(), ProcessingStatus.FAILED_TERMINAL);
        failedCount.incrementAndGet();
        failedDlqCounter.increment();
        deadLetterPort.recordFailedEngagement(claimedTask.engagement(), event, cause, attemptsMade);
    }

    private void waitForFuture(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Individual task exceptions are handled inside processNextFromFairQueue;
            // rethrow only if an unrecoverable Error escaped.
            if (e.getCause() instanceof Error err) {
                throw err;
            }
        }
    }

    private record ClaimedEngagementTask(EngagementRef engagement, IdempotencyKey key) {
    }
}
