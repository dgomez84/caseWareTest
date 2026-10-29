package com.caseware.fanout.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable event emitted when a new version of a Product Template is published on a market branch.
 *
 * @param eventId         unique identifier of the publication event
 * @param templateId      product template identifier (shared across firms)
 * @param marketBranch    branch name in the Git-like template version DAG (e.g. "CA-IFRS", "EU-AUDIT")
 * @param targetVersion   newly published template version identifier (e.g. "v7")
 * @param summaryArtifactUri immutable URI of the verified human-readable diff summary in WORM storage
 * @param publishedAt     timestamp when the version was published
 */
public record TemplatePublishedEvent(
        String eventId,
        String templateId,
        String marketBranch,
        String targetVersion,
        String summaryArtifactUri,
        Instant publishedAt
) {
    public TemplatePublishedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(templateId, "templateId must not be null");
        Objects.requireNonNull(marketBranch, "marketBranch must not be null");
        Objects.requireNonNull(targetVersion, "targetVersion must not be null");
        Objects.requireNonNull(summaryArtifactUri, "summaryArtifactUri must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
