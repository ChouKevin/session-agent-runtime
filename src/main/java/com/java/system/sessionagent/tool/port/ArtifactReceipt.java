package com.java.system.sessionagent.tool.port;

import java.util.UUID;

/** Internal successful-output association. Never inferred from model-visible JSON. */
public record ArtifactReceipt(UUID artifactId, UUID observationToken, ToolExecutionContext context) {}
