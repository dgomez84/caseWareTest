package com.caseware.fanout.model;

/**
 * Execution state of an individual engagement fan-out task.
 */
public enum ProcessingStatus {
    IN_PROGRESS,
    COMPLETED,
    SKIPPED_ALREADY_COMPLETED,
    SKIPPED_OBSOLETE_VERSION,
    FAILED_TERMINAL
}
