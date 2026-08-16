package com.java.system.sessionagent.tool.application;

import com.java.system.sessionagent.tool.domain.ToolResult;

@FunctionalInterface
public interface ToolExecutor<T> {

    ToolResult execute(T input);
}
