package com.java.system.sessionagent.mcp;

import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void composes_names_but_routes_with_the_retained_connection_and_raw_name() {
        RecordingClient client = new RecordingClient();
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        manager.publish("semantic", client, List.of(tool("search_code", "Search code")));

        ToolSnapshot snapshot = catalog.snapshot();
        snapshot.invoke(new ToolName("semantic_search_code"), Map.of("query", "fees"));

        assertThat(client.lastRequest().name()).isEqualTo("search_code");
        assertThat(client.lastRequest().arguments()).containsEntry("query", "fees");
    }

    @Test
    void exposes_no_bindings_when_no_connections_have_been_published() {
        McpToolCatalog catalog = new McpToolCatalog(manager(), OBJECT_MAPPER);

        assertThat(catalog.snapshot().definitions()).isEmpty();
    }

    @Test
    void snapshot_retains_the_source_client_after_the_manager_replaces_its_connection_view() {
        RecordingClient originalClient = new RecordingClient();
        RecordingClient replacementClient = new RecordingClient();
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        manager.publish("semantic", originalClient, List.of(tool("search_code", "Search code")));
        ToolSnapshot originalSnapshot = catalog.snapshot();
        manager.publish("semantic", replacementClient, List.of(tool("search_code", "Search code")));

        originalSnapshot.invoke(new ToolName("semantic_search_code"), Map.of("query", "first"));
        catalog.snapshot().invoke(new ToolName("semantic_search_code"), Map.of("query", "second"));

        assertThat(originalClient.lastRequest().arguments()).containsEntry("query", "first");
        assertThat(replacementClient.lastRequest().arguments()).containsEntry("query", "second");
    }

    @Test
    void does_not_degrade_a_replacement_when_an_older_view_rejected_a_binding() {
        RecordingClient originalClient = new RecordingClient();
        RecordingClient replacementClient = new RecordingClient();
        McpConnectionManager manager = manager();
        manager.publish("semantic", originalClient, List.of(tool("not.valid", "Invalid")));
        McpConnectionManager.View rejectedView = manager.view();
        manager.publish("semantic", replacementClient, List.of(tool("search_code", "Search code")));

        manager.markDegraded("semantic", rejectedView.connections().get("semantic"));

        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.AVAILABLE);
        assertThat(manager.view().connections().get("semantic").client()).contains(replacementClient);
    }

    @Test
    void rejects_both_ambiguous_bindings_without_affecting_unrelated_tools() {
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        manager.publish("semantic", new RecordingClient(), List.of(
                tool("search_code", "Search code"),
                tool("search_code", "Search code again"),
                tool("get_fact", "Get fact")));

        ToolSnapshot snapshot = catalog.snapshot();

        assertThat(snapshot.definitions()).extracting(definition -> definition.name().value())
                .containsExactly("semantic_get_fact");
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.DEGRADED);
    }

    @Test
    void publishes_safe_diagnostics_and_unrelated_routes_before_a_snapshot_is_requested() {
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);

        manager.publish("semantic", new RecordingClient(), Arrays.asList(
                tool("search_code", "Search code"), null, tool("not.valid", "Invalid"), tool("get_fact", "Get fact")));
        manager.publish("billing", new RecordingClient(), List.of(tool("lookup_invoice", "Lookup invoice")));

        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.DEGRADED);
        assertThat(manager.diagnostic("billing").state()).isEqualTo(McpConnectionState.AVAILABLE);
        assertThat(catalog.snapshot().definitions()).extracting(definition -> definition.name().value())
                .containsExactly("semantic_search_code", "semantic_get_fact", "billing_lookup_invoice");
    }

    @Test
    void withholds_a_malformed_schema_before_any_snapshot_can_reach_the_worker() {
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        McpSchema.Tool malformed = new McpSchema.Tool("bad_schema", null, "Malformed",
                Map.of("type", new Object()), Map.of(), null, Map.of());

        manager.publish("semantic", new RecordingClient(), List.of(tool("search_code", "Search code"), malformed));

        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.DEGRADED);
        assertThat(catalog.snapshot().definitions()).extracting(definition -> definition.name().value())
                .containsExactly("semantic_search_code");
    }

    @Test
    void withholds_invalid_format_and_length_without_exposing_connection_configuration_in_diagnostics() {
        McpConnectionProperties properties = new McpConnectionProperties(
                Map.of("semantic", new McpConnectionProperties.Connection(
                        true, URI.create("https://mcp.example/custom/mcp"),
                        Map.of("Authorization", "Bearer secret-token"))),
                null, null, null, null, null);
        McpConnectionManager manager = manager(properties);
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        manager.publish("semantic", new RecordingClient(), List.of(
                tool("not.valid", "Invalid"),
                tool("a".repeat(60), "Too long")));

        ToolSnapshot snapshot = catalog.snapshot();

        assertThat(snapshot.definitions()).isEmpty();
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.DEGRADED);
        assertThat(manager.diagnostic("semantic").message())
                .doesNotContain("https://mcp.example/custom/mcp", "Authorization", "secret-token");
    }

    @Test
    void preserves_multiple_independent_connections_when_one_has_no_tools() {
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        manager.publish("empty", new RecordingClient(), List.of());
        manager.publish("semantic", new RecordingClient(), List.of(tool("search_code", "Search code")));
        manager.publish("billing", new RecordingClient(), List.of(tool("lookup_invoice", "Lookup invoice")));

        assertThat(catalog.snapshot().definitions()).extracting(definition -> definition.name().value())
                .containsExactly("semantic_search_code", "billing_lookup_invoice");
        assertThat(manager.diagnostic("empty").state()).isEqualTo(McpConnectionState.AVAILABLE);
    }

    @Test
    void uses_the_raw_name_when_the_mcp_description_is_absent() {
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        manager.publish("semantic", new RecordingClient(), List.of(tool("get_fact", null)));

        assertThat(catalog.snapshot().definitions()).singleElement()
                .extracting(definition -> definition.description())
                .isEqualTo("get_fact");
    }

    @Test
    void declares_only_the_safe_operational_states() {
        assertThat(EnumSet.allOf(McpConnectionState.class)).containsExactlyInAnyOrder(
                McpConnectionState.DISABLED,
                McpConnectionState.CONNECTING,
                McpConnectionState.AVAILABLE,
                McpConnectionState.DEGRADED,
                McpConnectionState.UNAVAILABLE,
                McpConnectionState.STOPPED);
    }

    @Test
    void converts_an_unclassified_tool_runtime_failure_to_a_safe_protocol_output() {
        RecordingClient client = new RecordingClient();
        client.failToolCall();
        McpConnectionManager manager = manager();
        McpToolCatalog catalog = new McpToolCatalog(manager, OBJECT_MAPPER);
        manager.publish("semantic", client, List.of(tool("search_code", "Search code")));

        assertThat(catalog.snapshot().invoke(new ToolName("semantic_search_code"), Map.of("query", "fees")).asStructuredValue())
                .isEqualTo(Map.of(
                        "isError", true,
                        "result", Map.of("code", "TOOL_PROTOCOL_ERROR", "message", "The tool returned an invalid response.")));
    }

    private static McpSchema.Tool tool(String name, String description) {
        return new McpSchema.Tool(name, null, description, Map.of("type", "object"), Map.of(), null, Map.of());
    }

    private static McpConnectionManager manager() {
        return manager(new McpConnectionProperties(Map.of(), null, null, null, null, null));
    }

    private static McpConnectionManager manager(McpConnectionProperties properties) {
        return new McpConnectionManager(properties, connection -> {
            throw new IllegalStateException("MCP lifecycle is not configured");
        }, new ManualTaskScheduler(), java.time.Clock.systemUTC(), OBJECT_MAPPER, Runnable::run);
    }

    private static final class RecordingClient implements McpConnectionClient {

        private final AtomicReference<McpSchema.CallToolRequest> lastRequest = new AtomicReference<>();
        private boolean toolCallFails;

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
            if (toolCallFails) {
                throw new IllegalStateException("https://mcp.example/custom/mcp Authorization: Bearer secret-token");
            }
            lastRequest.set(request);
            return new McpSchema.CallToolResult(List.of(), false, null, Map.of());
        }

        McpSchema.CallToolRequest lastRequest() {
            return lastRequest.get();
        }

        private void failToolCall() {
            toolCallFails = true;
        }

        @Override
        public void close() {
        }
    }

    private static final class ManualTaskScheduler implements org.springframework.scheduling.TaskScheduler {

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, org.springframework.scheduling.Trigger trigger) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, java.time.Instant startTime) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task, java.time.Instant startTime, java.time.Duration period) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable task, java.time.Duration period) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task, java.time.Instant startTime, java.time.Duration delay) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, java.time.Duration delay) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }
    }
}
