package com.java.system.sessionagent.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class McpConnectionManager {

    private static final String WITHHELD_BINDING_MESSAGE = "One or more MCP tool bindings were withheld.";

    private volatile View view;

    public McpConnectionManager() {
        this(new McpConnectionProperties(Map.of(), null, null, null, null, null));
    }

    public McpConnectionManager(McpConnectionProperties properties) {
        Assert.notNull(properties, "MCP connection properties must not be null");
        LinkedHashMap<String, ConnectionView> initialConnections = new LinkedHashMap<>();
        for (Map.Entry<String, McpConnectionProperties.Connection> entry : properties.connections().entrySet()) {
            McpConnectionState initialState = entry.getValue().enabled()
                    ? McpConnectionState.CONNECTING : McpConnectionState.DISABLED;
            initialConnections.put(entry.getKey(), ConnectionView.withoutClient(initialState));
        }
        view = new View(initialConnections);
    }

    public void publish(String connectionName, McpConnectionClient client, List<McpSchema.Tool> tools) {
        McpConnectionProperties.validateConnectionName(connectionName);
        Assert.notNull(client, "MCP connection client must not be null");
        Assert.notNull(tools, "MCP tools must not be null");
        replace(connectionName, new ConnectionView(Optional.of(client), tools, new Diagnostic(McpConnectionState.AVAILABLE, "")));
    }

    public View view() {
        return view;
    }

    public Diagnostic diagnostic(String connectionName) {
        McpConnectionProperties.validateConnectionName(connectionName);
        ConnectionView connection = view.connections().get(connectionName);
        Assert.notNull(connection, "MCP connection is not known");
        return connection.diagnostic();
    }

    synchronized void markDegraded(String connectionName, ConnectionView expectedConnection) {
        McpConnectionProperties.validateConnectionName(connectionName);
        Assert.notNull(expectedConnection, "Expected MCP connection view must not be null");
        ConnectionView connection = view.connections().get(connectionName);
        Assert.notNull(connection, "MCP connection is not known");
        if (!connection.equals(expectedConnection)) {
            return;
        }
        replace(connectionName, connection.withDiagnostic(new Diagnostic(McpConnectionState.DEGRADED, WITHHELD_BINDING_MESSAGE)));
    }

    private synchronized void replace(String connectionName, ConnectionView replacement) {
        LinkedHashMap<String, ConnectionView> nextConnections = new LinkedHashMap<>(view.connections());
        nextConnections.put(connectionName, replacement);
        view = new View(nextConnections);
    }

    public record View(Map<String, ConnectionView> connections) {

        public View {
            Assert.notNull(connections, "MCP connections must not be null");
            connections = Collections.unmodifiableMap(new LinkedHashMap<>(connections));
        }
    }

    public record ConnectionView(
            Optional<McpConnectionClient> client,
            List<McpSchema.Tool> tools,
            Diagnostic diagnostic) {

        public ConnectionView {
            Assert.notNull(client, "MCP connection client view must not be null");
            Assert.notNull(tools, "MCP connection tools must not be null");
            Assert.notNull(diagnostic, "MCP connection diagnostic must not be null");
            tools = List.copyOf(tools);
        }

        static ConnectionView withoutClient(McpConnectionState state) {
            return new ConnectionView(Optional.empty(), List.of(), new Diagnostic(state, ""));
        }

        private ConnectionView withDiagnostic(Diagnostic replacementDiagnostic) {
            return new ConnectionView(client, tools, replacementDiagnostic);
        }
    }

    public record Diagnostic(McpConnectionState state, String message) {

        public Diagnostic {
            Assert.notNull(state, "MCP connection state must not be null");
            message = Objects.requireNonNull(message, "MCP connection diagnostic message must not be null");
        }
    }
}
