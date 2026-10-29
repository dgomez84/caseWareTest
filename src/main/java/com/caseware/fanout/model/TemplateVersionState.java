package com.caseware.fanout.model;

/**
 * Lifecycle state of a Product Template version in the Git-like version DAG.
 */
public enum TemplateVersionState {
    /** Active head version eligible for propagation. */
    PUBLISHED,
    /** Version was withdrawn after publication; pending fan-out tasks must abort. */
    WITHDRAWN,
    /** A newer version on the same market branch has superseded this version. */
    SUPERSEDED
}
