package com.caseware.fanout.port;

import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.ProcessingStatus;

import java.util.Optional;

/**
 * Port for atomic idempotency tracking and checkpointing across worker restarts and duplicate SQS deliveries.
 */
public interface FanOutIdempotencyStorePort {

    /**
     * Atomically claims the given key for processing if and only if it is not currently {@code IN_PROGRESS}
     * or {@code COMPLETED}.
     *
     * @return {@code true} if the caller acquired the claim and should proceed with the 1-minute downstream call;
     *         {@code false} if already completed or currently claimed by another worker.
     */
    boolean tryClaim(IdempotencyKey key);

    /**
     * Marks the given key as successfully completed.
     */
    void markCompleted(IdempotencyKey key);

    /**
     * Releases an in-progress claim after a terminal failure or version obsolescence so the final state is recorded.
     */
    void markTerminalState(IdempotencyKey key, ProcessingStatus terminalStatus);

    /**
     * Inspects the current recorded status for the key.
     */
    Optional<ProcessingStatus> getStatus(IdempotencyKey key);
}
