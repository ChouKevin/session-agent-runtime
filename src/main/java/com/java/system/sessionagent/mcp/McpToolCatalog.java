package com.java.system.sessionagent.mcp;

import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class McpToolCatalog implements ToolCatalog {

    private final McpConnectionManager connectionManager;
    private final McpToolResultMapper resultMapper;

    public McpToolCatalog(McpConnectionManager connectionManager, ObjectMapper objectMapper) {
        Assert.notNull(connectionManager, "MCP connection manager must not be null");
        Assert.notNull(objectMapper, "Object mapper must not be null");
        this.connectionManager = connectionManager;
        this.resultMapper = new McpToolResultMapper(objectMapper);
    }

    @Override
    public ToolSnapshot snapshot() {
        McpConnectionManager.AcquiredView acquiredView = connectionManager.acquireView();
        try {
            McpConnectionManager.View managerView = acquiredView.view();
            LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
            for (Map.Entry<String, McpConnectionManager.ConnectionView> entry : managerView.connections().entrySet()) {
                String connectionName = entry.getKey();
                McpConnectionManager.ConnectionView connection = entry.getValue();
                Optional<McpConnectionClient> client = connection.client();
                if (client.isEmpty()) {
                    continue;
                }
                for (McpSchema.Tool tool : connection.tools()) {
                    Route route = validRoute(connectionName, client.orElseThrow(), tool).orElseThrow();
                    routes.put(route.exposedName(), route);
                }
            }

            List<ToolBinding> bindings = new ArrayList<>();
            for (Route route : routes.values()) {
                bindings.add(binding(route));
            }
            return new ToolSnapshot(bindings, acquiredView::close);
        } catch (RuntimeException | Error exception) {
            acquiredView.close();
            throw exception;
        }
    }

    private static Optional<Route> validRoute(String connectionName, McpConnectionClient client, McpSchema.Tool tool) {
        String rawToolName = tool.name();
        String exposedName = connectionName + "_" + rawToolName;
        String description = StringUtils.hasText(tool.description()) ? tool.description() : rawToolName;
        return Optional.of(new Route(connectionName, rawToolName, exposedName, description, tool.inputSchema(), client));
    }

    private ToolBinding binding(Route route) {
        ToolDefinition definition = ToolDefinition.fromExposedName(
                route.exposedName(), route.description(), route.inputSchema());
        return new ToolBinding(definition, arguments -> invoke(route, arguments));
    }

    private ToolOutput invoke(Route route, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(route.rawToolName(), arguments);
        try {
            Optional<McpSchema.CallToolResult> result = Optional.ofNullable(route.client().callTool(request));
            return result.map(resultMapper::map).orElseGet(resultMapper::protocolFailure);
        } catch (RuntimeException exception) {
            return resultMapper.mapRuntimeFailure(exception).orElseGet(resultMapper::protocolFailure);
        }
    }

    private record Route(
            String connectionName,
            String rawToolName,
            String exposedName,
            String description,
            Map<String, Object> inputSchema,
            McpConnectionClient client) {
    }
}
