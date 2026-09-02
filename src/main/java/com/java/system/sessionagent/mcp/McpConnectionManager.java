package com.java.system.sessionagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.tool.port.ToolOutput;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class McpConnectionManager {

    private static final String WITHHELD_BINDING_MESSAGE = "One or more MCP tool bindings were withheld.";
    private static final String CONNECTION_FAILURE_MESSAGE = "The MCP connection is unavailable.";
    private static final Pattern PORTABLE_TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private final McpConnectionProperties properties;
    private final McpConnectionClientFactory clientFactory;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final McpToolResultMapper resultMapper;
    private final Executor workExecutor;
    private final Optional<ExecutorService> ownedWorkExecutor;
    private final Map<String, PendingWork> reconnectWork = new LinkedHashMap<>();
    private final Map<String, PendingWork> refreshWork = new LinkedHashMap<>();
    private final Map<String, Integer> reconnectFailures = new LinkedHashMap<>();
    private final Map<String, List<McpSchema.Tool>> rawToolsByConnection = new LinkedHashMap<>();
    private final Set<String> inFlightConnections = new java.util.HashSet<>();
    private final Set<McpConnectionClient> ownedClients = Collections.newSetFromMap(new IdentityHashMap<>());

    private volatile View view;
    private boolean started;
    private boolean stopped;

    public McpConnectionManager(
            McpConnectionProperties properties,
            TaskScheduler scheduler,
            Clock clock,
            ObjectMapper objectMapper) {
        this(properties, McpConnectionClientFactory.sdk(properties), scheduler, clock, new McpToolResultMapper(objectMapper),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    McpConnectionManager(
            McpConnectionProperties properties,
            McpConnectionClientFactory clientFactory,
            TaskScheduler scheduler,
            Clock clock,
            ObjectMapper objectMapper,
            Executor workExecutor) {
        this(properties, clientFactory, scheduler, clock, new McpToolResultMapper(objectMapper), workExecutor, Optional.empty());
    }

    private McpConnectionManager(
            McpConnectionProperties properties,
            McpConnectionClientFactory clientFactory,
            TaskScheduler scheduler,
            Clock clock,
            McpToolResultMapper resultMapper,
            ExecutorService workExecutor) {
        this(properties, clientFactory, scheduler, clock, resultMapper, workExecutor, Optional.of(workExecutor));
    }

    private McpConnectionManager(
            McpConnectionProperties properties,
            McpConnectionClientFactory clientFactory,
            TaskScheduler scheduler,
            Clock clock,
            McpToolResultMapper resultMapper,
            Executor workExecutor,
            Optional<ExecutorService> ownedWorkExecutor) {
        Assert.notNull(properties, "MCP connection properties must not be null");
        Assert.notNull(clientFactory, "MCP connection client factory must not be null");
        Assert.notNull(scheduler, "MCP task scheduler must not be null");
        Assert.notNull(clock, "MCP clock must not be null");
        Assert.notNull(resultMapper, "MCP result mapper must not be null");
        Assert.notNull(workExecutor, "MCP lifecycle work executor must not be null");
        Assert.notNull(ownedWorkExecutor, "MCP owned work executor must not be null");
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.scheduler = scheduler;
        this.clock = clock;
        this.resultMapper = resultMapper;
        this.workExecutor = workExecutor;
        this.ownedWorkExecutor = ownedWorkExecutor;
        LinkedHashMap<String, ConnectionView> initialConnections = new LinkedHashMap<>();
        for (Map.Entry<String, McpConnectionProperties.Connection> entry : properties.connections().entrySet()) {
            McpConnectionState initialState = entry.getValue().enabled()
                    ? McpConnectionState.CONNECTING : McpConnectionState.DISABLED;
            initialConnections.put(entry.getKey(), ConnectionView.withoutClient(initialState));
        }
        view = new View(initialConnections);
    }

    public synchronized void start() {
        if (started || stopped) {
            return;
        }
        started = true;
        for (Map.Entry<String, McpConnectionProperties.Connection> entry : properties.connections().entrySet()) {
            if (entry.getValue().enabled()) {
                scheduleAttempt(entry.getKey(), Duration.ZERO);
            }
        }
    }

    public void publish(String connectionName, McpConnectionClient client, List<McpSchema.Tool> tools) {
        McpConnectionProperties.validateConnectionName(connectionName);
        Assert.notNull(client, "MCP connection client must not be null");
        Assert.notNull(tools, "MCP tools must not be null");
        boolean closeClient;
        synchronized (this) {
            closeClient = stopped;
            if (!closeClient) {
                ownedClients.add(client);
                replacePublished(connectionName, client, tools);
            }
        }
        if (closeClient) {
            closeQuietly(client);
        }
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
        replace(connectionName, connection.withDiagnostic(Diagnostic.withheldBinding()));
    }

    public void stop() {
        List<McpConnectionClient> clientsToClose;
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
            cancelScheduledWork();
            LinkedHashMap<String, ConnectionView> stoppedConnections = new LinkedHashMap<>();
            for (String connectionName : view.connections().keySet()) {
                stoppedConnections.put(connectionName, ConnectionView.withoutClient(McpConnectionState.STOPPED));
            }
            view = new View(stoppedConnections);
            rawToolsByConnection.clear();
            clientsToClose = new ArrayList<>(ownedClients);
            ownedClients.clear();
        }
        ownedWorkExecutor.ifPresent(ExecutorService::shutdownNow);
        closeWithinTimeout(clientsToClose);
    }

    private synchronized void scheduleAttempt(String connectionName, Duration delay) {
        cancelPendingWork(reconnectWork.remove(connectionName));
        PendingWork pendingWork = new PendingWork();
        reconnectWork.put(connectionName, pendingWork);
        ScheduledFuture<?> scheduledTask = scheduler.schedule(
                () -> attemptDue(connectionName, pendingWork), clock.instant().plus(delay));
        pendingWork.setFuture(scheduledTask);
    }

    private void attemptDue(String connectionName, PendingWork pendingWork) {
        synchronized (this) {
            if (stopped || reconnectWork.get(connectionName) != pendingWork) { // cs-allow identity selects current scheduled work
                return;
            }
            reconnectWork.remove(connectionName);
        }
        launchConnectionWork(connectionName, () -> connect(connectionName));
    }

    private void launchConnectionWork(String connectionName, Runnable work) {
        synchronized (this) {
            if (stopped || !inFlightConnections.add(connectionName)) {
                return;
            }
        }
        try {
            workExecutor.execute(() -> {
                try {
                    work.run();
                } finally {
                    synchronized (McpConnectionManager.this) {
                        inFlightConnections.remove(connectionName);
                    }
                }
            });
        } catch (RuntimeException exception) {
            synchronized (this) {
                inFlightConnections.remove(connectionName);
            }
            markUnavailableAndReconnect(connectionName, exception);
        }
    }

    private void connect(String connectionName) {
        synchronized (this) {
            if (stopped) {
                return;
            }
            replace(connectionName, ConnectionView.withoutClient(McpConnectionState.CONNECTING));
        }
        McpConnectionClient client;
        try {
            client = clientFactory.create(properties.connections().get(connectionName));
        } catch (RuntimeException exception) {
            markUnavailableAndReconnect(connectionName, exception);
            return;
        }
        if (!retainCandidate(client)) {
            closeQuietly(client);
            return;
        }
        try {
            McpSchema.ListToolsResult listedTools = Objects.requireNonNull(client.listTools(), "MCP tools response must not be null");
            List<McpSchema.Tool> tools = Objects.requireNonNull(listedTools.tools(), "MCP tools list must not be null");
            publishConnected(connectionName, client, tools);
        } catch (RuntimeException exception) {
            closeOwnedClient(client);
            markUnavailableAndReconnect(connectionName, exception);
        }
    }

    private boolean retainCandidate(McpConnectionClient client) {
        synchronized (this) {
            if (stopped) {
                return false;
            }
            ownedClients.add(client);
            return true;
        }
    }

    private void refreshDue(String connectionName, McpConnectionClient client, PendingWork pendingWork) {
        synchronized (this) {
            if (stopped || refreshWork.get(connectionName) != pendingWork) { // cs-allow identity selects current recurring work
                return;
            }
        }
        launchConnectionWork(connectionName, () -> refresh(connectionName, client));
    }

    private void refresh(String connectionName, McpConnectionClient client) {
        try {
            McpSchema.ListToolsResult listedTools = Objects.requireNonNull(client.listTools(), "MCP tools response must not be null");
            List<McpSchema.Tool> tools = Objects.requireNonNull(listedTools.tools(), "MCP tools list must not be null");
            synchronized (this) {
                if (stopped) {
                    return;
                }
                replacePublished(connectionName, client, tools);
            }
        } catch (RuntimeException exception) {
            markUnavailableAndReconnect(connectionName, exception);
        }
    }

    private void publishConnected(String connectionName, McpConnectionClient client, List<McpSchema.Tool> tools) {
        boolean closeClient;
        synchronized (this) {
            closeClient = stopped;
            if (!closeClient) {
                reconnectFailures.remove(connectionName);
                replacePublished(connectionName, client, tools);
                scheduleRefresh(connectionName, client);
            }
        }
        if (closeClient) {
            closeOwnedClient(client);
        }
    }

    private synchronized void scheduleRefresh(String connectionName, McpConnectionClient client) {
        cancelPendingWork(refreshWork.remove(connectionName));
        PendingWork pendingWork = new PendingWork();
        refreshWork.put(connectionName, pendingWork);
        ScheduledFuture<?> scheduledTask = scheduler.scheduleAtFixedRate(
                () -> refreshDue(connectionName, client, pendingWork),
                clock.instant().plus(properties.refreshInterval()), properties.refreshInterval());
        pendingWork.setFuture(scheduledTask);
    }

    private void markUnavailableAndReconnect(String connectionName, RuntimeException exception) {
        synchronized (this) {
            if (stopped) {
                return;
            }
            cancelPendingWork(refreshWork.remove(connectionName));
            ToolOutput failure = resultMapper.mapRuntimeFailure(exception).orElseGet(resultMapper::connectionFailure);
            replace(connectionName, ConnectionView.withoutClient(new Diagnostic(
                    McpConnectionState.UNAVAILABLE, failureCode(failure), CONNECTION_FAILURE_MESSAGE)));
            int failures = reconnectFailures.merge(connectionName, 1, Integer::sum);
            scheduleAttempt(connectionName, reconnectDelay(failures));
        }
    }

    private String failureCode(ToolOutput failure) {
        Object result = failure.result();
        if (result instanceof Map<?, ?> resultMap) {
            Object code = resultMap.get("code");
            if (code instanceof String safeCode) {
                return safeCode;
            }
        }
        return "TOOL_CONNECTION_FAILED";
    }

    private Duration reconnectDelay(int failures) {
        Duration delay = properties.initialBackoff();
        for (int attempt = 1; attempt < failures; attempt++) {
            if (delay.compareTo(properties.maximumBackoff()) >= 0) {
                return properties.maximumBackoff();
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return properties.maximumBackoff();
            }
        }
        return delay.compareTo(properties.maximumBackoff()) > 0 ? properties.maximumBackoff() : delay;
    }

    private synchronized void cancelScheduledWork() {
        for (PendingWork pendingWork : reconnectWork.values()) {
            cancelPendingWork(pendingWork);
        }
        for (PendingWork pendingWork : refreshWork.values()) {
            cancelPendingWork(pendingWork);
        }
        reconnectWork.clear();
        refreshWork.clear();
    }

    private static void cancelPendingWork(PendingWork pendingWork) {
        if (Objects.nonNull(pendingWork)) {
            pendingWork.cancel();
        }
    }

    private void closeOwnedClient(McpConnectionClient client) {
        boolean closeClient;
        synchronized (this) {
            closeClient = ownedClients.remove(client);
        }
        if (closeClient) {
            closeQuietly(client);
        }
    }

    private static void closeQuietly(McpConnectionClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // Cleanup must not expose MCP SDK failures to lifecycle callers.
        }
    }

    private void closeWithinTimeout(List<McpConnectionClient> clientsToClose) {
        ExecutorService closeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        for (McpConnectionClient client : clientsToClose) {
            closeExecutor.submit(() -> closeQuietly(client));
        }
        closeExecutor.shutdown();
        try {
            closeExecutor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            closeExecutor.shutdownNow();
        }
    }

    private synchronized void replace(String connectionName, ConnectionView replacement) {
        if (replacement.client().isEmpty()) {
            rawToolsByConnection.remove(connectionName);
        }
        LinkedHashMap<String, ConnectionView> nextConnections = new LinkedHashMap<>(view.connections());
        nextConnections.put(connectionName, replacement);
        view = new View(nextConnections);
    }

    private void replacePublished(String connectionName, McpConnectionClient client, List<McpSchema.Tool> rawTools) {
        rawToolsByConnection.put(connectionName, Collections.unmodifiableList(new ArrayList<>(rawTools)));
        LinkedHashMap<String, ConnectionView> candidates = new LinkedHashMap<>(view.connections());
        candidates.put(connectionName, new ConnectionView(Optional.of(client), List.of(), Diagnostic.available()));
        view = validatedView(candidates);
    }

    private View validatedView(Map<String, ConnectionView> candidates) {
        LinkedHashMap<String, List<RouteCandidate>> routesByName = new LinkedHashMap<>();
        LinkedHashSet<String> degradedConnections = new LinkedHashSet<>();
        for (Map.Entry<String, ConnectionView> entry : candidates.entrySet()) {
            String connectionName = entry.getKey();
            ConnectionView connection = entry.getValue();
            if (connection.client().isEmpty()) {
                continue;
            }
            for (McpSchema.Tool tool : rawToolsByConnection.getOrDefault(connectionName, List.of())) {
                if (!isValidTool(connectionName, tool)) {
                    degradedConnections.add(connectionName);
                    continue;
                }
                String exposedName = connectionName + "_" + tool.name();
                routesByName.computeIfAbsent(exposedName, ignored -> new ArrayList<>())
                        .add(new RouteCandidate(connectionName, tool));
            }
        }
        LinkedHashMap<String, List<McpSchema.Tool>> acceptedTools = new LinkedHashMap<>();
        for (Map.Entry<String, List<RouteCandidate>> entry : routesByName.entrySet()) {
            List<RouteCandidate> routes = entry.getValue();
            if (routes.size() > 1) {
                for (RouteCandidate route : routes) {
                    degradedConnections.add(route.connectionName());
                }
                continue;
            }
            RouteCandidate route = routes.getFirst();
            acceptedTools.computeIfAbsent(route.connectionName(), ignored -> new ArrayList<>()).add(route.tool());
        }
        LinkedHashMap<String, ConnectionView> validatedConnections = new LinkedHashMap<>();
        for (Map.Entry<String, ConnectionView> entry : candidates.entrySet()) {
            String connectionName = entry.getKey();
            ConnectionView connection = entry.getValue();
            if (connection.client().isEmpty()) {
                validatedConnections.put(connectionName, connection);
                continue;
            }
            Diagnostic diagnostic = degradedConnections.contains(connectionName)
                    ? Diagnostic.withheldBinding() : Diagnostic.available();
            List<McpSchema.Tool> accepted = acceptedTools.getOrDefault(connectionName, List.of());
            validatedConnections.put(connectionName, new ConnectionView(connection.client(), accepted, diagnostic));
        }
        return new View(validatedConnections);
    }

    private static boolean isValidTool(String connectionName, McpSchema.Tool tool) {
        if (Objects.isNull(tool) || !org.springframework.util.StringUtils.hasText(tool.name())) {
            return false;
        }
        if (Objects.isNull(tool.inputSchema())) {
            return false;
        }
        return PORTABLE_TOOL_NAME.matcher(connectionName + "_" + tool.name()).matches()
                && isGenericJsonValue(tool.inputSchema());
    }

    private static boolean isGenericJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String) || !isGenericJsonValue(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!isGenericJsonValue(item)) {
                    return false;
                }
            }
            return true;
        }
        return value instanceof String || value instanceof Number || value instanceof Boolean || Objects.isNull(value);
    }

    public record View(Map<String, ConnectionView> connections) {

        public View {
            Assert.notNull(connections, "MCP connections must not be null");
            connections = Collections.unmodifiableMap(new LinkedHashMap<>(connections));
        }
    }

    public record ConnectionView(Optional<McpConnectionClient> client, List<McpSchema.Tool> tools, Diagnostic diagnostic) {

        public ConnectionView {
            Assert.notNull(client, "MCP connection client view must not be null");
            Assert.notNull(tools, "MCP connection tools must not be null");
            Assert.notNull(diagnostic, "MCP connection diagnostic must not be null");
            tools = List.copyOf(tools);
        }

        static ConnectionView withoutClient(McpConnectionState state) {
            return new ConnectionView(Optional.empty(), List.of(), new Diagnostic(state, "", ""));
        }

        static ConnectionView withoutClient(Diagnostic diagnostic) {
            return new ConnectionView(Optional.empty(), List.of(), diagnostic);
        }

        private ConnectionView withDiagnostic(Diagnostic replacementDiagnostic) {
            return new ConnectionView(client, tools, replacementDiagnostic);
        }
    }

    private record RouteCandidate(String connectionName, McpSchema.Tool tool) {
    }

    public record Diagnostic(McpConnectionState state, String code, String message) {

        public Diagnostic {
            Assert.notNull(state, "MCP connection state must not be null");
            code = Objects.requireNonNull(code, "MCP connection diagnostic code must not be null");
            message = Objects.requireNonNull(message, "MCP connection diagnostic message must not be null");
        }

        private static Diagnostic available() {
            return new Diagnostic(McpConnectionState.AVAILABLE, "", "");
        }

        private static Diagnostic withheldBinding() {
            return new Diagnostic(McpConnectionState.DEGRADED, "TOOL_PROTOCOL_ERROR", WITHHELD_BINDING_MESSAGE);
        }
    }

    private static final class PendingWork {

        private ScheduledFuture<?> future;

        private void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        private void cancel() {
            Optional.ofNullable(future).ifPresent(scheduledFuture -> scheduledFuture.cancel(false));
        }
    }
}
