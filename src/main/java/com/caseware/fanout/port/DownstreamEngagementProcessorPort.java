package com.caseware.fanout.port;

import com.caseware.fanout.exception.DownstreamProcessingException;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.TemplatePublishedEvent;

/**
 * Outbound port representing the downstream Engagement Management System owned by another team.
 * <p>
 * Hard Constraints from Problem Statement:
 * <ul>
 *   <li>Opening/processing an engagement takes ~1 minute per engagement.</li>
 *   <li>The downstream service has strictly limited capacity and must be protected from overload.</li>
 * </ul>
 */
@FunctionalInterface
public interface DownstreamEngagementProcessorPort {

    /**
     * Evaluates or synchronizes the target engagement against the newly published template version.
     *
     * @param engagement the target engagement reference
     * @param event      the template publication event
     * @throws DownstreamProcessingException if a retryable, throttled, or permanent failure occurs
     */
    void processEngagementUpdate(EngagementRef engagement, TemplatePublishedEvent event)
            throws DownstreamProcessingException;
}
