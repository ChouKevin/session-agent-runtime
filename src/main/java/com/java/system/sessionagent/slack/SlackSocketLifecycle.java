package com.java.system.sessionagent.slack;

import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SlackSocketLifecycle implements SmartLifecycle {

    private final SlackSocketClient socketClient;
    private final boolean enabled;
    private final Duration reconnectDelay;
    private final Duration shutdownTimeout;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<SlackLifecycleState> state;

    public SlackSocketLifecycle(SlackSocketClient socketClient, SlackProperties properties) {
        Assert.notNull(socketClient, "Slack socket client must not be null");
        Assert.notNull(properties, "Slack properties must not be null");
        this.socketClient = socketClient;
        this.enabled = properties.enabled();
        this.reconnectDelay = properties.reconnectDelay();
        this.shutdownTimeout = properties.shutdownTimeout();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-agent-slack-socket");
            thread.setDaemon(true);
            return thread;
        });
        this.state = new AtomicReference<>(enabled ? SlackLifecycleState.STOPPED : SlackLifecycleState.DISABLED);
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        connect();
    }

    @Override
    public void stop() {
        if (!enabled || !running.compareAndSet(true, false)) {
            return;
        }
        executor.execute(() -> {
            try {
                socketClient.stop();
            } catch (Exception ignored) {
                // Socket shutdown is best effort and intentionally keeps provider failure details out of logs.
            } finally {
                state.set(SlackLifecycleState.STOPPED);
            }
        });
        executor.shutdown();
        try {
            executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
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

    private void connect() {
        executor.execute(() -> {
            if (!running.get()) {
                return;
            }
            state.set(SlackLifecycleState.CONNECTING);
            try {
                socketClient.start();
                state.set(SlackLifecycleState.AVAILABLE);
            } catch (Exception ignored) {
                state.set(SlackLifecycleState.DEGRADED);
                if (running.get()) {
                    executor.schedule(this::connect, reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        });
    }
}
