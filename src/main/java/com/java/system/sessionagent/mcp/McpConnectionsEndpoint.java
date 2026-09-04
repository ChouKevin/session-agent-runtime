package com.java.system.sessionagent.mcp;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Endpoint(id = "mcpConnections")
public final class McpConnectionsEndpoint {

    private final McpConnectionManager connectionManager;

    public McpConnectionsEndpoint(McpConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @ReadOperation
    public Map<String, Object> connections() {
        LinkedHashMap<String, Object> connections = new LinkedHashMap<>();
        for (Map.Entry<String, McpConnectionManager.ConnectionView> entry : connectionManager.view().connections().entrySet()) {
            McpConnectionManager.ConnectionView connection = entry.getValue();
            McpConnectionManager.Diagnostic diagnostic = connection.diagnostic();
            LinkedHashMap<String, Object> details = new LinkedHashMap<>();
            details.put("state", diagnostic.state().name());
            details.put("toolCount", connection.tools().size());
            if (StringUtils.hasText(diagnostic.code())) {
                details.put("code", diagnostic.code());
                details.put("message", diagnostic.message());
            }
            connections.put(entry.getKey(), Collections.unmodifiableMap(details));
        }
        return Map.of("connections", Collections.unmodifiableMap(connections));
    }
}
