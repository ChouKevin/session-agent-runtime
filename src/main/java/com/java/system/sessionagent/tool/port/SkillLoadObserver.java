package com.java.system.sessionagent.tool.port;

import java.util.UUID;

/** Starts an operational read attempt under the current work authority, never a successful outcome. */
@FunctionalInterface
public interface SkillLoadObserver {
    UUID started(ToolInvocation invocation, String uri);
}
