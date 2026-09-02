package com.java.system.sessionagent.mcp;

@FunctionalInterface
interface McpConnectionClientFactory {

    McpConnectionClient create(McpConnectionProperties.Connection connection);
}
