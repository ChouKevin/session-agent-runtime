package com.java.system.sessionagent.artifact.port;

import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ArtifactReceipt;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ArtifactStore {
    int MAX_BYTES = 1_048_576;
    int MAX_JOB_BYTES = 5 * MAX_BYTES;
    int MAX_ARTIFACTS = 10;
    Saved create(ToolInvocation invocation, String title, String extension, String mediaType,
                 byte[] content, String toolName, Map<String, Object> arguments,
                 Map<String, Object> disclosures);
    /** Only outputs committed to actual observations and released by successful final text. */
    List<Artifact> released(UUID sessionId, UUID messageJobId);

    record Saved(ArtifactReceipt receipt, Map<String, Object> result) {}
    record Artifact(UUID artifactId, String title, String filename, String mediaType, byte[] content) {}
}
