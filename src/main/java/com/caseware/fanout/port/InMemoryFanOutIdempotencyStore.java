package com.caseware.fanout.port;

import com.caseware.fanout.model.IdempotencyKey;
import com.caseware.fanout.model.ProcessingStatus;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe, lock-free reference implementation of {@link FanOutIdempotencyStorePort}
 * backed by a {@link ConcurrentHashMap} (simulating DynamoDB conditional writes in production).
 */
public class InMemoryFanOutIdempotencyStore implements FanOutIdempotencyStorePort {

    private final ConcurrentMap<IdempotencyKey, ProcessingStatus> store = new ConcurrentHashMap<>();

    @Override
    public boolean tryClaim(IdempotencyKey key) {
        AtomicBoolean claimed = new AtomicBoolean(false);
        store.compute(key, (k, existing) -> {
            if (existing == ProcessingStatus.COMPLETED || existing == ProcessingStatus.IN_PROGRESS) {
                return existing;
            }
            claimed.set(true);
            return ProcessingStatus.IN_PROGRESS;
        });
        return claimed.get();
    }

    @Override
    public void markCompleted(IdempotencyKey key) {
        store.put(key, ProcessingStatus.COMPLETED);
    }

    @Override
    public void markTerminalState(IdempotencyKey key, ProcessingStatus terminalStatus) {
        store.put(key, terminalStatus);
    }

    @Override
    public Optional<ProcessingStatus> getStatus(IdempotencyKey key) {
        return Optional.ofNullable(store.get(key));
    }
}
