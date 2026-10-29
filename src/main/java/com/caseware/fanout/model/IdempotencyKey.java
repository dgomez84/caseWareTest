package com.caseware.fanout.model;

import java.util.Objects;

/**
 * Composite idempotency key ensuring at-most-once downstream execution per
 * (templateId, targetVersion, engagementId) transition.
 */
public record IdempotencyKey(
        String templateId,
        String targetVersion,
        String engagementId
) {
    public IdempotencyKey {
        Objects.requireNonNull(templateId, "templateId must not be null");
        Objects.requireNonNull(targetVersion, "targetVersion must not be null");
        Objects.requireNonNull(engagementId, "engagementId must not be null");
    }
}
