package com.java.system.sessionagent.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpConnectionsEndpointTest {

    @Test
    void exposes_only_safe_connection_diagnostics_without_configuration_secrets() {
        McpConnectionManager manager = new McpConnectionManager(
                new McpConnectionProperties(Map.of(), null, null, null, null, null), connection -> {
                    throw new IllegalStateException("MCP lifecycle is not configured");
                }, Mockito.mock(TaskScheduler.class), java.time.Clock.systemUTC(),
                new com.fasterxml.jackson.databind.ObjectMapper(), Runnable::run);
        manager.publish("semantic", new EmptyClient(), List.of(new McpSchema.Tool(
                "search_code", null, "Search", Map.of("type", "object"), Map.of(), null, Map.of())));
        McpConnectionsEndpoint endpoint = new McpConnectionsEndpoint(manager);

        Map<String, Object> response = endpoint.connections();

        assertThat(response).isEqualTo(Map.of("connections", Map.of("semantic", Map.of(
                "state", "AVAILABLE", "toolCount", 1))));
        assertThat(response.toString()).doesNotContain("http", "Authorization", "secret-token");
    }

    private static final class EmptyClient implements McpConnectionClient {

        @Override
        public McpSchema.InitializeResult initialize() {
            return null;
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            return new McpSchema.ListToolsResult(List.of(), null);
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            return new McpSchema.CallToolResult(List.of(), false, Map.of(), Map.of());
        }

        @Override
        public void close() {
        }
    }
}
