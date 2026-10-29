package com.caseware.fanout.resilience;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Governs access to the capacity-constrained downstream Engagement Management System (~1 min/call).
 * <p>
 * Combines:
 * <ol>
 *   <li>A fair {@link Semaphore} enforcing a strict ceiling on concurrent in-flight downstream requests.</li>
 *   <li>A global adaptive cooldown window triggered when the downstream system returns HTTP 429 / throttling signals.</li>
 * </ol>
 */
public class DownstreamCapacityGovernor {

    private final int maxConcurrency;
    private final Semaphore concurrencySemaphore;
    private final AtomicLong cooldownUntilNanoTime = new AtomicLong(0L);
    private final AtomicInteger currentInFlight = new AtomicInteger(0);
    private final AtomicInteger peakObservedInFlight = new AtomicInteger(0);

    public DownstreamCapacityGovernor(int maxConcurrency) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        this.maxConcurrency = maxConcurrency;
        this.concurrencySemaphore = new Semaphore(maxConcurrency, true);
    }

    /**
     * Waits for any active global throttle cooldown to expire and then acquires a concurrency permit.
     */
    public void acquirePermit() throws InterruptedException {
        awaitGlobalCooldownIfNeeded();
        concurrencySemaphore.acquire();
        try {
            awaitGlobalCooldownIfNeeded();
            int inFlight = currentInFlight.incrementAndGet();
            peakObservedInFlight.accumulateAndGet(inFlight, Math::max);
        } catch (InterruptedException e) {
            concurrencySemaphore.release();
            throw e;
        }
    }

    /**
     * Releases a previously acquired concurrency permit.
     * MUST be called before any retry backoff sleep so permits are never held by sleeping threads.
     */
    public void releasePermit() {
        currentInFlight.decrementAndGet();
        concurrencySemaphore.release();
    }

    /**
     * Signals that the downstream service returned a throttle response (e.g. HTTP 429 Retry-After),
     * advancing the global cooldown window for all virtual threads.
     */
    public void registerThrottleSignal(Duration retryAfter) {
        long targetNano = System.nanoTime() + Math.max(0L, retryAfter.toNanos());
        cooldownUntilNanoTime.accumulateAndGet(targetNano, Math::max);
    }

    private void awaitGlobalCooldownIfNeeded() throws InterruptedException {
        while (true) {
            long remainingNanos = cooldownUntilNanoTime.get() - System.nanoTime();
            if (remainingNanos <= 0L) {
                return;
            }
            TimeUnit.NANOSECONDS.sleep(remainingNanos);
        }
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public int currentInFlight() {
        return currentInFlight.get();
    }

    public int peakObservedInFlight() {
        return peakObservedInFlight.get();
    }
}
