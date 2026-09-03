package com.java.system.sessionagent.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

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
    void retires_a_replaced_client_without_handles_immediately() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient original = new RecordingClient(List.of(tool("search_code")));
        RecordingClient replacement = new RecordingClient(List.of(tool("lookup_invoice")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> original, scheduler);
        manager.publish("semantic", original, List.of(tool("search_code")));

        manager.publish("semantic", replacement, List.of(tool("lookup_invoice")));

        assertThat(original.closeCount()).isEqualTo(1);
        assertThat(replacement.closeCount()).isZero();
    }

    @Test
    void retains_a_replaced_client_until_the_last_acquired_view_is_released() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient original = new RecordingClient(List.of(tool("search_code")));
        RecordingClient replacement = new RecordingClient(List.of(tool("lookup_invoice")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> original, scheduler);
        manager.publish("semantic", original, List.of(tool("search_code")));
        McpConnectionManager.AcquiredView first = manager.acquireView();
        McpConnectionManager.AcquiredView second = manager.acquireView();

        manager.publish("semantic", replacement, List.of(tool("lookup_invoice")));
        first.close();

        assertThat(original.closeCount()).isZero();
        second.close();
        second.close();

        assertThat(original.closeCount()).isEqualTo(1);
    }

    @Test
    void acquires_a_view_before_a_waiting_replacement_can_retire_its_client() throws InterruptedException {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient original = new RecordingClient(List.of(tool("search_code")));
        RecordingClient replacement = new RecordingClient(List.of(tool("lookup_invoice")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> original, scheduler);
        manager.publish("semantic", original, List.of(tool("search_code")));
        CountDownLatch publisherStarted = new CountDownLatch(1);
        McpConnectionManager.AcquiredView acquired;
        synchronized (manager) {
            Thread publisher = Thread.startVirtualThread(() -> {
                publisherStarted.countDown();
                manager.publish("semantic", replacement, List.of(tool("lookup_invoice")));
            });
            assertThat(publisherStarted.await(1, TimeUnit.SECONDS)).isTrue();
            acquired = manager.acquireView();
            publisher.join(100);
            assertThat(publisher.isAlive()).isTrue();
        }

        assertThat(acquired.view().connections().get("semantic").client()).contains(original);
        assertThat(awaitCondition(() -> manager.view().connections().get("semantic").client()
                        .filter(currentClient -> currentClient == replacement).isPresent(), // cs-allow concrete client identity is the lifecycle contract
                Duration.ofSeconds(1))).isTrue();
        assertThat(original.closeCount()).isZero();
        acquired.close();
        assertThat(original.closeCount()).isEqualTo(1);
    }

    @Test
    void republishes_the_same_client_without_retiring_it() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient client = new RecordingClient(List.of(tool("search_code")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> client, scheduler);
        manager.publish("semantic", client, List.of(tool("search_code")));

        manager.publish("semantic", client, List.of(tool("lookup_invoice")));

        assertThat(client.closeCount()).isZero();
        assertThat(manager.view().connections().get("semantic").tools()).extracting(McpSchema.Tool::name)
                .containsExactly("lookup_invoice");
    }

    @Test
    void ignores_a_stale_refresh_failure_after_a_newer_client_has_replaced_it() throws InterruptedException {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        BlockingRefreshClient original = new BlockingRefreshClient(List.of(tool("search_code")));
        RecordingClient replacement = new RecordingClient(List.of(tool("lookup_invoice")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> original, scheduler);
        manager.start();
        scheduler.runDueTasks();
        Thread refresh = Thread.startVirtualThread(scheduler::runRecurringTasks);
        assertThat(original.refreshStarted.await(1, TimeUnit.SECONDS)).isTrue();

        manager.publish("semantic", replacement, List.of(tool("lookup_invoice")));
        original.releaseRefresh.countDown();
        refresh.join(1_000);

        assertThat(manager.view().connections().get("semantic").client()).contains(replacement);
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.AVAILABLE);
    }

    @Test
    void retains_a_due_fallback_reconnect_until_the_failing_refresh_lifecycle_completes() throws InterruptedException {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        BlockingCloseClient original = new BlockingCloseClient(List.of(tool("search_code")));
        RecordingClient replacement = new RecordingClient(List.of(tool("lookup_invoice")));
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger completedLifecycleWork = new AtomicInteger();
        CountDownLatch initialLifecycleCompleted = new CountDownLatch(1);
        CountDownLatch refreshLifecycleCompleted = new CountDownLatch(1);
        CountDownLatch reconnectLifecycleCompleted = new CountDownLatch(1);
        Executor asynchronousExecutor = command -> Thread.startVirtualThread(() -> {
            command.run();
            int completedWork = completedLifecycleWork.incrementAndGet();
            if (completedWork == 1) { // cs-allow first task is the initial connection lifecycle work
                initialLifecycleCompleted.countDown();
            }
            if (completedWork == 2) { // cs-allow second task is the failed refresh lifecycle work
                refreshLifecycleCompleted.countDown();
            }
            if (completedWork == 3) { // cs-allow third task is the recovered reconnect lifecycle work
                reconnectLifecycleCompleted.countDown();
            }
        });
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> {
            if (factoryCalls.getAndIncrement() == 0) { // cs-allow first client establishes the refresh failure source
                return original;
            }
            return replacement;
        }, scheduler, Duration.ofSeconds(1), Duration.ofSeconds(60), asynchronousExecutor);
        manager.start();
        scheduler.runDueTasks();
        assertThat(original.listingCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(initialLifecycleCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        original.failNextList();
        scheduler.rejectNextOneShotSchedule();

        scheduler.runRecurringTasks();

        assertThat(original.closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        scheduler.advanceBy(Duration.ofSeconds(2));
        assertThat(factoryCalls).hasValue(1);
        original.releaseClose.countDown();
        assertThat(refreshLifecycleCompleted.await(1, TimeUnit.SECONDS)).isTrue();

        scheduler.runDueTasks();

        assertThat(reconnectLifecycleCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.AVAILABLE);
        assertThat(factoryCalls).hasValue(2);
    }

    @Test
    void retires_a_published_client_and_reconnects_when_refresh_scheduling_is_rejected() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        AtomicReference<McpConnectionManager> managerReference = new AtomicReference<>();
        RecordingClient original = new RecordingClient(List.of(tool("search_code"))) {
            @Override
            public void close() {
                McpConnectionManager connectionManager = managerReference.get();
                assertThat(connectionManager.view().connections().get("semantic").client()).isEmpty();
                super.close();
            }
        };
        RecordingClient replacement = new RecordingClient(List.of(tool("lookup_invoice")));
        AtomicInteger factoryCalls = new AtomicInteger();
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> {
            if (factoryCalls.getAndIncrement() == 0) {
                return original;
            }
            return replacement;
        }, scheduler);
        managerReference.set(manager);
        scheduler.rejectNextRefreshSchedule();

        org.assertj.core.api.Assertions.assertThatCode(() -> {
            manager.start();
            scheduler.runDueTasks();
        }).doesNotThrowAnyException();

        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.UNAVAILABLE);
        assertThat(original.closeCount()).isEqualTo(1);
        scheduler.advanceBy(Duration.ofSeconds(1));

        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.AVAILABLE);
        assertThat(manager.view().connections().get("semantic").client()).contains(replacement);
        assertThat(replacement.closeCount()).isZero();
    }

    @Test
    void closes_active_clients_on_shutdown_and_ignores_late_handle_release() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient client = new RecordingClient(List.of(tool("search_code")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> client, scheduler);
        manager.publish("semantic", client, List.of(tool("search_code")));
        McpConnectionManager.AcquiredView acquired = manager.acquireView();

        manager.stop();
        acquired.close();
        acquired.close();

        assertThat(client.closeCount()).isEqualTo(1);
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

    @Test
    void closes_an_unpublished_client_when_the_initial_tool_listing_fails() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient client = new RecordingClient(List.of(tool("search_code")));
        client.failNextList();
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> client, scheduler);

        manager.start();
        scheduler.runDueTasks();

        assertThat(client.closed()).isTrue();
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.UNAVAILABLE);
    }

    @Test
    void keeps_a_connection_degraded_when_tools_list_contains_malformed_entries() {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        RecordingClient client = new RecordingClient(Arrays.asList(tool("search_code"), null, tool("not.valid")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> client, scheduler);

        manager.start();
        scheduler.runDueTasks();

        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.DEGRADED);
        assertThat(manager.view().connections().get("semantic").tools()).extracting(McpSchema.Tool::name)
                .containsExactly("search_code");
    }

    @Test
    void closes_a_client_that_finishes_initial_listing_after_shutdown() throws InterruptedException {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        BlockingListClient client = new BlockingListClient(List.of(tool("search_code")));
        McpConnectionManager manager = manager(Map.of("semantic", connection()), configuredConnection -> client, scheduler);

        manager.start();
        Thread attemptThread = new Thread(scheduler::runDueTasks);
        attemptThread.start();
        assertThat(client.listingStarted.await(1, TimeUnit.SECONDS)).isTrue();
        manager.stop();
        client.releaseListing.countDown();
        attemptThread.join(1_000);

        assertThat(client.closed()).isTrue();
        assertThat(manager.diagnostic("semantic").state()).isEqualTo(McpConnectionState.STOPPED);
    }

    @Test
    void offloads_slow_connection_lifecycle_work_without_delaying_another_connection() throws InterruptedException {
        ControlledTaskScheduler scheduler = new ControlledTaskScheduler();
        BlockingListClient slowClient = new BlockingListClient(List.of(tool("search_code")));
        RecordingClient availableClient = new RecordingClient(List.of(tool("lookup_invoice")));
        Executor concurrentExecutor = command -> Thread.startVirtualThread(command);
        McpConnectionManager manager = manager(Map.of("slow", slowConnection(), "fast", connection()), configuredConnection -> {
            if (configuredConnection.url().getHost().equals("slow.example")) {
                return slowClient;
            }
            return availableClient;
        }, scheduler, Duration.ofSeconds(1), Duration.ofSeconds(60), concurrentExecutor);

        manager.start();
        scheduler.runDueTasks();

        assertThat(slowClient.listingStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(availableClient.listingCompleted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitCondition(
                () -> manager.diagnostic("fast").state() == McpConnectionState.AVAILABLE,
                Duration.ofSeconds(1))).isTrue();
        slowClient.releaseListing.countDown();
        manager.stop();
    }

    private static boolean awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10L);
        }
        return condition.getAsBoolean();
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
                Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper(), Runnable::run);
    }

    private static McpConnectionManager manager(
            Map<String, McpConnectionProperties.Connection> connections,
            McpConnectionClientFactory factory,
            ControlledTaskScheduler scheduler,
            Duration initialBackoff,
            Duration maximumBackoff,
            Executor executor) {
        McpConnectionProperties properties = new McpConnectionProperties(connections, Duration.ofSeconds(60),
                Duration.ofSeconds(1), initialBackoff, maximumBackoff, Duration.ofSeconds(5));
        return new McpConnectionManager(properties, factory, scheduler,
                Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC), new ObjectMapper(), executor);
    }

    private static McpConnectionProperties.Connection connection() {
        return new McpConnectionProperties.Connection(true, URI.create("https://semantic.example/custom/mcp"), Map.of());
    }

    private static McpConnectionProperties.Connection brokenConnection() {
        return new McpConnectionProperties.Connection(true, URI.create("https://broken.example/custom/mcp"), Map.of());
    }

    private static McpConnectionProperties.Connection slowConnection() {
        return new McpConnectionProperties.Connection(true, URI.create("https://slow.example/custom/mcp"), Map.of());
    }

    private static McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, null, name, Map.of("type", "object"), Map.of(), null, Map.of());
    }

    private static class RecordingClient implements McpConnectionClient {

        private final List<McpSchema.Tool> tools;
        private final AtomicBoolean failNextList = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger closeCount = new AtomicInteger();
        protected final CountDownLatch listingCompleted = new CountDownLatch(1);

        protected RecordingClient(List<McpSchema.Tool> tools) {
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
            listingCompleted.countDown();
            return new McpSchema.ListToolsResult(tools, null);
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
            return new McpSchema.CallToolResult(List.of(), false, Map.of(), Map.of());
        }

        @Override
        public void close() {
            closed.set(true);
            closeCount.incrementAndGet();
        }

        protected void failNextList() {
            failNextList.set(true);
        }

        protected boolean closed() {
            return closed.get();
        }

        private int closeCount() {
            return closeCount.get();
        }
    }

    private static final class BlockingListClient extends RecordingClient {

        private final CountDownLatch listingStarted = new CountDownLatch(1);
        private final CountDownLatch releaseListing = new CountDownLatch(1);

        private BlockingListClient(List<McpSchema.Tool> tools) {
            super(tools);
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            listingStarted.countDown();
            try {
                releaseListing.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("listing interrupted", exception);
            }
            return super.listTools();
        }
    }

    private static final class BlockingRefreshClient extends RecordingClient {

        private final AtomicInteger listCalls = new AtomicInteger();
        private final CountDownLatch refreshStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRefresh = new CountDownLatch(1);

        private BlockingRefreshClient(List<McpSchema.Tool> tools) {
            super(tools);
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            if (listCalls.incrementAndGet() == 2) { // cs-allow second invocation is the deterministic refresh boundary
                refreshStarted.countDown();
                try {
                    releaseRefresh.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("refresh interrupted", exception);
                }
                throw new IllegalStateException("stale refresh failed");
            }
            return super.listTools();
        }
    }

    private static final class BlockingCloseClient extends RecordingClient {

        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        private BlockingCloseClient(List<McpSchema.Tool> tools) {
            super(tools);
        }

        @Override
        public void close() {
            closeStarted.countDown();
            try {
                releaseClose.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("close interrupted", exception);
            }
            super.close();
        }
    }

    private static final class ControlledTaskScheduler implements TaskScheduler {

        private Instant now = Instant.parse("2026-09-03T00:00:00Z");
        private final List<ScheduledTask> tasks = new ArrayList<>();
        private final AtomicBoolean rejectNextOneShotSchedule = new AtomicBoolean();
        private final AtomicBoolean rejectNextRefreshSchedule = new AtomicBoolean();

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            throw new UnsupportedOperationException("Trigger scheduling is not used by MCP connections");
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            if (rejectNextOneShotSchedule.compareAndSet(true, false)) {
                throw new RejectedExecutionException("one-shot scheduling rejected");
            }
            ScheduledTask scheduledTask = new ScheduledTask(task, startTime, false);
            tasks.add(scheduledTask);
            return scheduledTask;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            if (rejectNextRefreshSchedule.compareAndSet(true, false)) {
                throw new RejectedExecutionException("refresh scheduling rejected");
            }
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

        private void rejectNextOneShotSchedule() {
            rejectNextOneShotSchedule.set(true);
        }

        private void rejectNextRefreshSchedule() {
            rejectNextRefreshSchedule.set(true);
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
