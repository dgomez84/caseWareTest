package com.caseware.fanout.port;

import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.TemplatePublishedEvent;

/**
 * Outbound port for recording permanently failed engagement fan-out tasks (e.g. SQS Dead-Letter Queue)
 * so poison pills never block the remaining engagements in a publish batch.
 */
@FunctionalInterface
public interface DeadLetterPort {

    void recordFailedEngagement(
            EngagementRef engagement,
            TemplatePublishedEvent event,
            Throwable terminalCause,
            int attemptsMade
    );
}
