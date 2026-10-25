package com.java.system.sessionagent.tool.port;

import java.util.Map;

@FunctionalInterface
public interface ToolExecutor {

    ToolOutput execute(ToolInvocation invocation, Map<String, Object> arguments);
}
