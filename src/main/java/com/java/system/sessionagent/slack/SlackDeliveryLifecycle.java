package com.java.system.sessionagent.slack;

import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SlackDeliveryLifecycle implements SmartLifecycle {

    private final SlackDeliveryWorker worker;
    private final boolean enabled;
    private final Duration pollDelay;
    private final Duration shutdownTimeout;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> poll = new AtomicReference<>();

    public SlackDeliveryLifecycle(SlackDeliveryWorker worker, SlackProperties properties) {
        Assert.notNull(worker, "Slack delivery worker must not be null");
        Assert.notNull(properties, "Slack properties must not be null");
        this.worker = worker;
        this.enabled = properties.enabled();
        this.pollDelay = properties.delivery().pollDelay();
        this.shutdownTimeout = properties.shutdownTimeout();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-agent-slack-delivery");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        poll.set(executor.scheduleWithFixedDelay(this::poll, 0, pollDelay.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Override
    public void stop() {
        running.set(false);
        ScheduledFuture<?> scheduledPoll = poll.getAndSet(null);
        if (scheduledPoll != null) { // cs-allow ScheduledFuture is nullable before lifecycle start
            scheduledPoll.cancel(true);
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

    private void poll() {
        if (!running.get()) {
            return;
        }
        try {
            worker.poll();
        } catch (RuntimeException ignored) {
            // Delivery remains recoverable from its durable row; provider details are deliberately not logged here.
        }
    }
}
