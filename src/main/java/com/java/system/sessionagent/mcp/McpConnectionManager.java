package com.java.system.sessionagent.mcp;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class McpConnectionManager {

    private static final String WITHHELD_BINDING_MESSAGE = "One or more MCP tool bindings were withheld.";
    private static final String CONNECTION_FAILURE_MESSAGE = "The MCP connection is unavailable.";

    private final McpConnectionProperties properties;
    private final McpConnectionClientFactory clientFactory;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final McpToolResultMapper resultMapper;
    private final Map<String, List<ScheduledFuture<?>>> scheduledWork = new LinkedHashMap<>();
    private final Map<String, ScheduledFuture<?>> refreshWork = new LinkedHashMap<>();
    private final Map<String, Integer> reconnectFailures = new LinkedHashMap<>();
    private final Set<McpConnectionClient> clients = Collections.newSetFromMap(new IdentityHashMap<>());

    private volatile View view;
    private boolean started;
    private boolean stopped;

    public McpConnectionManager() {
        this(new McpConnectionProperties(Map.of(), null, null, null, null, null),
                connection -> {
                    throw new IllegalStateException("No MCP client factory is configured");
                }, new NoOpTaskScheduler(), Clock.systemUTC(), new McpToolResultMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public McpConnectionManager(McpConnectionProperties properties) {
        this(properties, connection -> {
            throw new IllegalStateException("No MCP client factory is configured");
        }, new NoOpTaskScheduler(), Clock.systemUTC(), new McpToolResultMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public McpConnectionManager(
            McpConnectionProperties properties,
            TaskScheduler scheduler,
            Clock clock,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(properties, McpConnectionClientFactory.sdk(properties), scheduler, clock, new McpToolResultMapper(objectMapper));
    }

    McpConnectionManager(
            McpConnectionProperties properties,
            McpConnectionClientFactory clientFactory,
            TaskScheduler scheduler,
            Clock clock) {
        this(properties, clientFactory, scheduler, clock, new McpToolResultMapper(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    McpConnectionManager(
            McpConnectionProperties properties,
            McpConnectionClientFactory clientFactory,
            TaskScheduler scheduler,
            Clock clock,
            McpToolResultMapper resultMapper) {
        Assert.notNull(properties, "MCP connection properties must not be null");
        Assert.notNull(clientFactory, "MCP connection client factory must not be null");
        Assert.notNull(scheduler, "MCP task scheduler must not be null");
        Assert.notNull(clock, "MCP clock must not be null");
        Assert.notNull(resultMapper, "MCP result mapper must not be null");
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.scheduler = scheduler;
        this.clock = clock;
        this.resultMapper = resultMapper;
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
        synchronized (this) {
            clients.add(client);
            replace(connectionName, new ConnectionView(Optional.of(client), tools, Diagnostic.available()));
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
            clientsToClose = new ArrayList<>(clients);
        }
        closeWithinTimeout(clientsToClose);
    }

    private void scheduleAttempt(String connectionName, Duration delay) {
        Instant scheduledFor = clock.instant().plus(delay);
        ScheduledFuture<?> scheduledTask = scheduler.schedule(() -> connect(connectionName), scheduledFor);
        scheduledWork.computeIfAbsent(connectionName, ignored -> new ArrayList<>()).add(scheduledTask);
    }

    private void connect(String connectionName) {
        synchronized (this) {
            if (stopped) {
                return;
            }
            replace(connectionName, ConnectionView.withoutClient(McpConnectionState.CONNECTING));
        }
        try {
            McpConnectionClient client = clientFactory.create(properties.connections().get(connectionName));
            McpSchema.ListToolsResult listedTools = Objects.requireNonNull(client.listTools(), "MCP tools response must not be null");
            List<McpSchema.Tool> tools = Objects.requireNonNull(listedTools.tools(), "MCP tools list must not be null");
            publishConnected(connectionName, client, tools);
        } catch (RuntimeException exception) {
            markUnavailableAndReconnect(connectionName, exception);
        }
    }

    private void refresh(String connectionName, McpConnectionClient client) {
        try {
            McpSchema.ListToolsResult listedTools = Objects.requireNonNull(client.listTools(), "MCP tools response must not be null");
            List<McpSchema.Tool> tools = Objects.requireNonNull(listedTools.tools(), "MCP tools list must not be null");
            synchronized (this) {
                if (stopped) {
                    return;
                }
                replace(connectionName, new ConnectionView(Optional.of(client), tools, Diagnostic.available()));
            }
        } catch (RuntimeException exception) {
            markUnavailableAndReconnect(connectionName, exception);
        }
    }

    private void publishConnected(String connectionName, McpConnectionClient client, List<McpSchema.Tool> tools) {
        synchronized (this) {
            clients.add(client);
            if (stopped) {
                return;
            }
            reconnectFailures.remove(connectionName);
            replace(connectionName, new ConnectionView(Optional.of(client), tools, Diagnostic.available()));
            ScheduledFuture<?> refreshTask = scheduler.scheduleAtFixedRate(
                    () -> refresh(connectionName, client), clock.instant().plus(properties.refreshInterval()), properties.refreshInterval());
            refreshWork.put(connectionName, refreshTask);
            scheduledWork.computeIfAbsent(connectionName, ignored -> new ArrayList<>()).add(refreshTask);
        }
    }

    private void markUnavailableAndReconnect(String connectionName, RuntimeException exception) {
        synchronized (this) {
            if (stopped) {
                return;
            }
            cancelRefresh(connectionName);
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

    private synchronized void cancelRefresh(String connectionName) {
        Optional.ofNullable(refreshWork.remove(connectionName)).ifPresent(task -> task.cancel(false));
    }

    private synchronized void cancelScheduledWork() {
        for (List<ScheduledFuture<?>> tasks : scheduledWork.values()) {
            for (ScheduledFuture<?> task : tasks) {
                task.cancel(false);
            }
        }
        scheduledWork.clear();
        refreshWork.clear();
    }

    private void closeWithinTimeout(List<McpConnectionClient> clientsToClose) {
        ExecutorService closeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        for (McpConnectionClient client : clientsToClose) {
            closeExecutor.submit(client::close);
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
            return new ConnectionView(Optional.empty(), List.of(), new Diagnostic(state, "", ""));
        }

        static ConnectionView withoutClient(Diagnostic diagnostic) {
            return new ConnectionView(Optional.empty(), List.of(), diagnostic);
        }

        private ConnectionView withDiagnostic(Diagnostic replacementDiagnostic) {
            return new ConnectionView(client, tools, replacementDiagnostic);
        }
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

    private static final class NoOpTaskScheduler implements TaskScheduler {

        @Override
        public ScheduledFuture<?> schedule(Runnable task, org.springframework.scheduling.Trigger trigger) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException("MCP lifecycle is not configured");
        }
    }
}
