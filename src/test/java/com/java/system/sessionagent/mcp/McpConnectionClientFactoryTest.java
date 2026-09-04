package com.java.system.sessionagent.mcp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpConnectionClientFactoryTest {

    @Test
    void splits_the_exact_streamable_http_path_and_query_instead_of_using_the_sdk_default_endpoint() {
        McpConnectionClientFactory.Endpoint endpoint = McpConnectionClientFactory.splitEndpoint(
                URI.create("https://mcp.example:9443/custom/mcp?tenant=payments"));

        assertThat(endpoint.origin()).isEqualTo("https://mcp.example:9443/");
        assertThat(endpoint.pathAndQuery()).isEqualTo("/custom/mcp?tenant=payments");
    }

    @Test
    void rejects_an_endpoint_that_cannot_be_safely_represented_by_the_sdk_transport() {
        assertThatIllegalArgumentException().isThrownBy(() -> McpConnectionClientFactory.splitEndpoint(
                URI.create("https://token@mcp.example/custom/mcp#fragment")))
                .withMessage("MCP endpoint cannot safely be represented by the streamable HTTP transport");
    }

    @Test
    void applies_configured_headers_to_each_sdk_request_customization() {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://mcp.example/custom/mcp"));

        McpConnectionClientFactory.applyHeaders(request, Map.of("Authorization", "Bearer token", "X-Tenant", "payments"));

        assertThat(request.build().headers().map()).containsEntry("Authorization", List.of("Bearer token"))
                .containsEntry("X-Tenant", List.of("payments"));
    }

    @Test
    void closes_the_sdk_client_when_initialization_fails() {
        AtomicBoolean closed = new AtomicBoolean();
        McpConnectionProperties properties = new McpConnectionProperties(Map.of(), null, null, null, null, null);
        McpConnectionClientFactory factory = McpConnectionClientFactory.sdk(properties, transport -> new McpConnectionClientFactory.SdkClient() {

            @Override
            public io.modelcontextprotocol.spec.McpSchema.InitializeResult initialize() {
                throw new IllegalStateException("initialization failed");
            }

            @Override
            public io.modelcontextprotocol.spec.McpSchema.ListToolsResult listTools() {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.modelcontextprotocol.spec.McpSchema.CallToolResult callTool(
                    io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                closed.set(true);
            }
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> factory.create(
                new McpConnectionProperties.Connection(true, URI.create("https://mcp.example/custom/mcp"), Map.of())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(closed.get()).isTrue();
    }
}
