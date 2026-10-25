package com.java.system.sessionagent.tool.port;

import java.util.Objects;

/** Runtime-owned identity and separate live, fenced work authority. Never model arguments. */
public record ToolInvocation(ToolExecutionContext context, ToolExecutionAuthority authority) {
    public ToolInvocation {
        Objects.requireNonNull(context);
        Objects.requireNonNull(authority);
    }
}
