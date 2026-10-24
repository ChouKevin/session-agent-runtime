package com.java.system.sessionagent.slack.application;

import com.java.system.sessionagent.slack.configuration.SlackProgressProperties;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.SmartLifecycle;

/** Owns a scheduler independent of final-answer delivery and message execution. */
public final class SlackProgressLifecycle implements SmartLifecycle {
    private final SlackProgressWorker worker;
    private final boolean enabled;
    private final Duration pollDelay;
    private final Duration shutdownTimeout;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> poll = new AtomicReference<>();

    public SlackProgressLifecycle(SlackProgressWorker worker, SlackProperties slack, SlackProgressProperties progress) {
        this.worker = Objects.requireNonNull(worker, "Progress worker must not be null");
        Objects.requireNonNull(slack, "Slack properties must not be null");
        Objects.requireNonNull(progress, "Progress properties must not be null");
        this.enabled = slack.enabled();
        this.pollDelay = progress.pollDelay();
        this.shutdownTimeout = slack.shutdownTimeout();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "session-agent-slack-progress");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        poll.set(executor.scheduleWithFixedDelay(this::poll, 0, Math.max(1, pollDelay.toMillis()), TimeUnit.MILLISECONDS));
    }

    @Override
    public void stop() {
        running.set(false);
        java.util.Optional.ofNullable(poll.getAndSet(null)).ifPresent(value -> value.cancel(true));
        executor.shutdownNow();
        try {
            executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public int getPhase() { return Integer.MAX_VALUE; }

    @Override
    public boolean isAutoStartup() { return true; }

    private void poll() {
        if (!running.get()) {
            return;
        }
        try {
            worker.poll();
        } catch (RuntimeException ignored) {
            // Durable claims recover after expiry; never surface provider or database details here.
        }
    }

}
