package com.caseware.fanout.resilience;

import com.caseware.fanout.model.EngagementRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Mitigates the extreme tenant skew described in the problem statement
 * (median firm: ~40 engagements vs. largest firm: ~40,000 engagements).
 * <p>
 * Without tenant-aware ordering, a single 40,000-engagement firm at the head of a batch
 * would monopolize the downstream concurrency semaphore for hours (Head-of-Line Blocking),
 * starving hundreds of smaller firms. This scheduler interleaves engagements in Round-Robin
 * order across firms so smaller firms finish within minutes.
 */
public final class FairTenantScheduler {

    private FairTenantScheduler() {
    }

    /**
     * Interleaves the given engagements in Round-Robin order grouped by {@link EngagementRef#firmId()}.
     */
    public static List<EngagementRef> interleaveByFirmRoundRobin(List<EngagementRef> engagements) {
        if (engagements == null || engagements.size() <= 1) {
            return engagements == null ? List.of() : List.copyOf(engagements);
        }

        Map<String, Queue<EngagementRef>> queuesByFirm = new LinkedHashMap<>();
        for (EngagementRef ref : engagements) {
            queuesByFirm.computeIfAbsent(ref.firmId(), k -> new ArrayDeque<>()).add(ref);
        }

        List<EngagementRef> interleaved = new ArrayList<>(engagements.size());
        Queue<Queue<EngagementRef>> activeFirmQueues = new ArrayDeque<>(queuesByFirm.values());

        while (!activeFirmQueues.isEmpty()) {
            Queue<EngagementRef> firmQueue = activeFirmQueues.poll();
            EngagementRef next = firmQueue.poll();
            if (next != null) {
                interleaved.add(next);
            }
            if (!firmQueue.isEmpty()) {
                activeFirmQueues.offer(firmQueue);
            }
        }

        return interleaved;
    }
}
