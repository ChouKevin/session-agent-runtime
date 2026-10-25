package com.java.system.sessionagent.artifact.port;

import java.util.UUID;

@FunctionalInterface
public interface AttachmentRoute {
    boolean eligible(UUID sessionId, UUID messageJobId);
}
