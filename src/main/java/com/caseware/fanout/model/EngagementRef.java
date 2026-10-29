package com.caseware.fanout.model;

import java.util.Objects;

/**
 * Lightweight reference to an Engagement File derived from the regional metadata index.
 *
 * @param firmId          customer firm identifier (used for tenant-fair scheduling)
 * @param engagementId    unique identifier of the engagement file
 * @param templateId      product template used by this engagement
 * @param marketBranch    market branch tracked by this engagement
 * @param appliedVersion  template version currently applied to the engagement
 */
public record EngagementRef(
        String firmId,
        String engagementId,
        String templateId,
        String marketBranch,
        String appliedVersion
) {
    public EngagementRef {
        Objects.requireNonNull(firmId, "firmId must not be null");
        Objects.requireNonNull(engagementId, "engagementId must not be null");
        Objects.requireNonNull(templateId, "templateId must not be null");
        Objects.requireNonNull(marketBranch, "marketBranch must not be null");
        Objects.requireNonNull(appliedVersion, "appliedVersion must not be null");
    }
}
