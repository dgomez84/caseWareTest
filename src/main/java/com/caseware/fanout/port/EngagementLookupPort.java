package com.caseware.fanout.port;

import com.caseware.fanout.model.EngagementRef;

import java.util.List;

/**
 * Port for querying the regional EngagementMetadataIndex (DynamoDB) to discover active engagements
 * tracking a given Product Template and market branch.
 */
@FunctionalInterface
public interface EngagementLookupPort {

    /**
     * Finds all active engagements on the specified template and market branch that are not yet on {@code targetVersion}.
     */
    List<EngagementRef> findAffectedEngagements(String templateId, String marketBranch, String targetVersion);
}
