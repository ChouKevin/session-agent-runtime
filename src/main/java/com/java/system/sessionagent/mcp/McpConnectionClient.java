package com.java.system.sessionagent.mcp;

import io.modelcontextprotocol.spec.McpSchema;

interface McpConnectionClient extends AutoCloseable {

    McpSchema.InitializeResult initialize();

    McpSchema.ListToolsResult listTools();

    McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request);

    @Override
    void close();
}
