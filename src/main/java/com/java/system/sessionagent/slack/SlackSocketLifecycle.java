package com.java.system.sessionagent.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SlackSocketLifecycle implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackSocketLifecycle.class);
    private final SlackSocketClient socketClient;
    private final boolean enabled;
    private final Duration reconnectDelay;
    private final Duration shutdownTimeout;
    private final ScheduledExecutorService connectionExecutor;
    private final ExecutorService retirementExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<SlackLifecycleState> state;
    private final Object lifecycleMonitor = new Object();
    private long generation;

    public SlackSocketLifecycle(SlackSocketClient socketClient, SlackProperties properties) {
        Assert.notNull(socketClient, "Slack socket client must not be null");
        Assert.notNull(properties, "Slack properties must not be null");
        this.socketClient = socketClient;
        this.enabled = properties.enabled();
        this.reconnectDelay = properties.reconnectDelay();
        this.shutdownTimeout = properties.shutdownTimeout();
        this.connectionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-agent-slack-socket");
            thread.setDaemon(true);
            return thread;
        });
        this.retirementExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-agent-slack-retirement");
            thread.setDaemon(true);
            return thread;
        });
        this.state = new AtomicReference<>(enabled ? SlackLifecycleState.STOPPED : SlackLifecycleState.DISABLED);
    }

    @Override
    public void start() {
        if (!enabled) {
            return;
        }
        long startedGeneration;
        synchronized (lifecycleMonitor) {
            if (running.get()) {
                return;
            }
            running.set(true);
            generation++;
            startedGeneration = generation;
            state.set(SlackLifecycleState.CONNECTING);
        }
        logState("slack_connection_started", SlackLifecycleState.CONNECTING);
        connect(startedGeneration);
    }

    @Override
    public void stop() {
        if (!enabled) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (!running.get()) {
                return;
            }
            running.set(false);
            generation++;
            state.set(SlackLifecycleState.STOPPED);
        }
        retirementExecutor.execute(() -> {
            try {
                socketClient.stop();
            } catch (Exception ignored) {
                // Socket shutdown is best effort and intentionally keeps provider failure details out of logs.
            } finally {
                logState("slack_connection_stopped", SlackLifecycleState.STOPPED);
            }
        });
        connectionExecutor.shutdownNow();
        retirementExecutor.shutdown();
        try {
            retirementExecutor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    public SlackLifecycleState state() {
        return state.get();
    }

    private void connect(long connectionGeneration) {
        connectionExecutor.execute(() -> {
            if (!transition(connectionGeneration, SlackLifecycleState.CONNECTING)) {
                return;
            }
            logState("slack_connection_attempt", SlackLifecycleState.CONNECTING);
            try {
                socketClient.start(connectionListener(connectionGeneration));
            } catch (Exception ignored) {
                if (transition(connectionGeneration, SlackLifecycleState.DEGRADED)) {
                    logState("slack_connection_retry", SlackLifecycleState.DEGRADED);
                    try {
                        connectionExecutor.schedule(() -> connect(connectionGeneration), reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException rejectedExecutionException) {
                        // Stop retired the lifecycle between the running check and retry scheduling.
                    }
                }
            }
        });
    }

    private SlackSocketConnectionListener connectionListener(long connectionGeneration) {
        return new SlackSocketConnectionListener() {
            @Override
            public void connected() {
                if (transition(connectionGeneration, SlackLifecycleState.AVAILABLE)) {
                    logState("slack_connection_available", SlackLifecycleState.AVAILABLE);
                }
            }

            @Override
            public void disconnected() {
                if (transition(connectionGeneration, SlackLifecycleState.DEGRADED)) {
                    logState("slack_connection_degraded", SlackLifecycleState.DEGRADED);
                }
            }
        };
    }

    private boolean transition(long transitionGeneration, SlackLifecycleState nextState) {
        synchronized (lifecycleMonitor) {
            if (!running.get() || generation != transitionGeneration) {
                return false;
            }
            state.set(nextState);
            return true;
        }
    }

    private static void logState(String event, SlackLifecycleState state) {
        LOGGER.atInfo().addKeyValue("event", event)
                .addKeyValue("component", "SLACK_SOCKET")
                .addKeyValue("state", state.name()).log("runtime_lifecycle");
    }
}
