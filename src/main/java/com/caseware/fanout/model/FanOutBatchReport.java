package com.caseware.fanout.model;

/**
 * Aggregated outcome summary returned when a TemplatePublishedEvent fan-out batch completes.
 *
 * @param templateId              template identifier
 * @param targetVersion           target template version
 * @param totalDiscovered         total candidate engagements discovered for the template
 * @param completedCount          engagements successfully processed downstream
 * @param skippedIdempotentCount  engagements skipped because they were already completed or in flight
 * @param skippedObsoleteCount    engagements skipped because the version was withdrawn or superseded mid-batch
 * @param failedCount             engagements routed to the Dead Letter sink after permanent failure or retry exhaustion
 */
public record FanOutBatchReport(
        String templateId,
        String targetVersion,
        int totalDiscovered,
        int completedCount,
        int skippedIdempotentCount,
        int skippedObsoleteCount,
        int failedCount
) {
}
