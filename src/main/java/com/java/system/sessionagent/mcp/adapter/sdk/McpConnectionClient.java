package com.java.system.sessionagent.mcp.adapter.sdk;

import io.modelcontextprotocol.spec.McpSchema;

interface McpConnectionClient extends AutoCloseable {

    McpSchema.InitializeResult initialize();

    McpSchema.ListToolsResult listTools();

    McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request);

    @Override
    void close();
}
