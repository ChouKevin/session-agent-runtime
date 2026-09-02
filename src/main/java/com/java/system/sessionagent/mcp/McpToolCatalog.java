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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class McpToolCatalog implements ToolCatalog {

    private static final Pattern PORTABLE_TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

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
        McpConnectionManager.View managerView = connectionManager.view();
        LinkedHashMap<String, List<Route>> candidateRoutes = new LinkedHashMap<>();
        Set<String> degradedConnections = new LinkedHashSet<>();
        for (Map.Entry<String, McpConnectionManager.ConnectionView> entry : managerView.connections().entrySet()) {
            String connectionName = entry.getKey();
            McpConnectionManager.ConnectionView connection = entry.getValue();
            Optional<McpConnectionClient> client = connection.client();
            if (client.isEmpty()) {
                continue;
            }
            for (McpSchema.Tool tool : connection.tools()) {
                McpSchema.Tool discoveredTool = Objects.requireNonNull(tool, "MCP tool must not be null");
                Optional<Route> route = validRoute(connectionName, client.orElseThrow(), discoveredTool);
                if (route.isEmpty()) {
                    degradedConnections.add(connectionName);
                    continue;
                }
                Route retainedRoute = route.orElseThrow();
                candidateRoutes.computeIfAbsent(retainedRoute.exposedName(), ignored -> new ArrayList<>()).add(retainedRoute);
            }
        }

        List<ToolBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, List<Route>> entry : candidateRoutes.entrySet()) {
            List<Route> routes = entry.getValue();
            if (routes.size() > 1) {
                for (Route route : routes) {
                    degradedConnections.add(route.connectionName());
                }
                continue;
            }
            bindings.add(binding(routes.getFirst()));
        }
        for (String connectionName : degradedConnections) {
            connectionManager.markDegraded(connectionName, managerView.connections().get(connectionName));
        }
        return new ToolSnapshot(bindings);
    }

    private static Optional<Route> validRoute(String connectionName, McpConnectionClient client, McpSchema.Tool tool) {
        String rawToolName = tool.name();
        if (!StringUtils.hasText(rawToolName)) {
            return Optional.empty();
        }
        String exposedName = connectionName + "_" + rawToolName;
        if (!PORTABLE_TOOL_NAME.matcher(exposedName).matches()) {
            return Optional.empty();
        }
        if (Optional.ofNullable(tool.inputSchema()).isEmpty()) {
            return Optional.empty();
        }
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
