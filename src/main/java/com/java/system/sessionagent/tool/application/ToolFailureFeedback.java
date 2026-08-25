package com.java.system.sessionagent.tool.application;

import com.java.system.sessionagent.tool.json.StrictJsonCodec;

/** Canonical model-visible payloads for typed tool failures. */
public final class ToolFailureFeedback {

    private static final StrictJsonCodec JSON = new StrictJsonCodec();

    private ToolFailureFeedback() {
    }

    public static String revisionOutdated(ToolExecutionFailure.RevisionOutdatedDetails details) {
        return JSON.canonicalize(new RevisionOutdatedPayload(details.repositoryId(), details.requestedRevision(),
                details.currentRevision(), "Repository revision is outdated. Retry the same useful tool with currentRevision."));
    }

    private record RevisionOutdatedPayload(String repositoryId, String requestedRevision, String currentRevision,
                                           String message) {
    }
}
