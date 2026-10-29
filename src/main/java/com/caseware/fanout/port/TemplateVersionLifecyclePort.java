package com.caseware.fanout.port;

import com.caseware.fanout.model.TemplateVersionState;

/**
 * Port querying the local cached Template Version DAG to verify whether a version is still active
 * before executing an expensive 1-minute downstream engagement call.
 */
@FunctionalInterface
public interface TemplateVersionLifecyclePort {

    /**
     * Returns the current lifecycle state of the specified template version on its market branch.
     */
    TemplateVersionState getVersionState(String templateId, String marketBranch, String version);
}
