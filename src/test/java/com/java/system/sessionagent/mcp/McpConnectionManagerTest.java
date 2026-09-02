package com.java.system.sessionagent.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpConnectionManagerTest {

    @Test
    void isolates_an_unavailable_connection_while_another_connection_is_published() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient availableClient = new RecordingClient(List.of(tool("search_code")));
        McpConnectionManager manager = manager(
                Map.of("broken", brokenConnection(), "semantic", connection()),
                configuredConnection -> {
                    if (configuredConnection.url().getHost().equals("broken.example")) {
                        throw new IllegalStateException("secret endpoint failure");
                    }
                    return availableClient;
                }, scheduler);

        manager.start();
        scheduler.runDueTasks();

        assertThat(manager.diagnostic("broken").state()).isEqualTo(McpConnectionState.UNAVAILABLE);
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.AVAILABLE);
        assertThat(manager.view().connections().get("semantic").tools()).extracting(McpSchema.Tool::name)
                .containsExactly("search_code");
    }

    @Test
    void caps_exponential_reconnect_delays_at_configured_maximum() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        McpConnectionManager manager = manager(Map.of("broken", connection()),
                configuredConnection -> {
                    throw new IllegalStateException("connection refused");
                }, scheduler, Duration.ofSeconds(2), Duration.ofSeconds(5));

        manager.start();
        scheduler.runDueTasks();
        scheduler.advanceBy(Duration.ofSeconds(2));
        scheduler.advanceBy(Duration.ofSeconds(4));
        scheduler.advanceBy(Duration.ofSeconds(5));

        assertThat(scheduler.oneShotDelays()).containsExactly(
                Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void successful_reconnect_republishes_tools_and_refresh_retains_prior_snapshot_client() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient originalClient = new RecordingClient(List.of(tool("search_code")));
        RecordingClient replacementClient = new RecordingClient(List.of(tool("lookup_invoice")));
        AtomicInteger factoryCalls = new AtomicInteger();
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> {
            if (factoryCalls.getAndIncrement() == 0) {
                return originalClient;
            }
            return replacementClient;
        }, scheduler);

        manager.start();
        scheduler.runDueTasks();
        McpConnectionManager.View originalView = manager.view();
        originalClient.failNextList();
        scheduler.runRecurringTasks();
        scheduler.advanceBy(Duration.ofSeconds(1));

        assertThat(originalView.connections().get("semantic").client()).contains(originalClient);
        assertThat(manager.view().connections().get("semantic").client()).contains(replacementClient);
        assertThat(manager.view().connections().get("semantic").tools()).extracting(McpSchema.Tool::name)
                .containsExactly("lookup_invoice");
    }

    @Test
    void shutdown_cancels_schedules_closes_clients_and_marks_every_connection_stopped() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient client = new RecordingClient(List.of(tool("search_code")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> client, scheduler);

        manager.start();
        scheduler.runDueTasks();
        manager.stop();

        assertThat(client.closed()).isTrue();
        assertThat(scheduler.allFuturesCancelled()).isTrue();
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.STOPPED);
    }

    private static McpConnectionManager manager(
            Map<String, McpConnectionProperties.Connection> connections,
            McpConnectionClientFactory factory,
            ControlledTaskScheduler scheduler) {
        return manager(connections, factory, scheduler, Duration.ofSeconds(1), Duration.ofSeconds(60));
    }

    private static McpConnectionManager manager(
            Map<String, McpConnectionProperties.Connection> connections,
            McpConnectionClientFactory factory,
            ControlledTaskScheduler scheduler,
            Duration initialBackoff,
            Duration maximumBackoff) {
        McpConnectionProperties properties = new McpConnectionProperties(connections, Duration.ofSeconds(60),
                Duration.ofSeconds(1), initialBackoff, maximumBackoff, Duration.ofSeconds(5));
        return new McpConnectionManager(properties, factory, scheduler,
                Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC));
    }

    private static McpConnectionProperties.Connection connection() {
        return new McpConnectionProperties.Connection(true, URI.create("https://semantic.example/custom/mcp"), Map.of());
    }

    private static McpConnectionProperties.Connection brokenConnection() {
        return new McpConnectionProperties.Connection(true, URI.create("https://broken.example/custom/mcp"), Map.of());
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, null, name, Map.of("type", "object"), Map.of(), null, Map.of());
    }

    private static final class RecordingClient implements McpConnectionClient {

        private final List<McpSchema.Tool> tools;
        private final AtomicBoolean failNextList = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private RecordingClient(List<McpSchema.Tool> tools) {
            this.tools = tools;
        }

        @Override
        public McpSchema.InitializeResult initialize() {
            return null;
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            if (failNextList.compareAndSet(true, false)) {
                throw new IllegalStateException("connection lost");
            }
            return new McpSchema.ListToolsResult(tools, null);
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            return new McpSchema.CallToolResult(List.of(), false, Map.of(), Map.of());
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private void failNextList() {
            failNextList.set(true);
        }

        private boolean closed() {
            return closed.get();
        }
    }

    private static final class ControlledTaskScheduler implements TaskScheduler {

        private Instant now = Instant.parse("2026-09-03T00:00:00Z");
        private final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException("Trigger scheduling is not used by MCP connections");
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            ScheduledTask scheduledTask = new ScheduledTask(task, startTime, false);
            tasks.add(scheduledTask);
            return scheduledTask;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            ScheduledTask scheduledTask = new ScheduledTask(task, startTime, true);
            tasks.add(scheduledTask);
            return scheduledTask;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return scheduleAtFixedRate(task, now.plus(period), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            throw new UnsupportedOperationException("Fixed delay scheduling is not used by MCP connections");
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            throw new UnsupportedOperationException("Fixed delay scheduling is not used by MCP connections");
        }

        private void runDueTasks() {
            List<ScheduledTask> due = tasks.stream()
                    .filter(task -> !task.cancelled.get() && !task.recurring && !task.scheduledFor.isAfter(now))
                    .toList();
            for (ScheduledTask task : due) {
                task.cancelled.set(true);
                task.runnable.run();
            }
        }

        private void runRecurringTasks() {
            List<ScheduledTask> recurring = tasks.stream()
                    .filter(task -> !task.cancelled.get() && task.recurring)
                    .toList();
            for (ScheduledTask task : recurring) {
                task.runnable.run();
            }
        }

        private void advanceBy(Duration delay) {
            now = now.plus(delay);
            runDueTasks();
        }

        private List<Duration> oneShotDelays() {
            return tasks.stream().filter(task -> !task.recurring)
                    .map(task -> Duration.between(Instant.parse("2026-09-03T00:00:00Z"), task.scheduledFor)).toList();
        }

        private boolean allFuturesCancelled() {
            return tasks.stream().allMatch(task -> task.cancelled.get());
        }
    }

    private static final class ScheduledTask implements ScheduledFuture<Object> {

        private final Runnable runnable;
        private final Instant scheduledFor;
        private final boolean recurring;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ScheduledTask(Runnable runnable, Instant scheduledFor, boolean recurring) {
            this.runnable = runnable;
            this.scheduledFor = scheduledFor;
            this.recurring = recurring;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
