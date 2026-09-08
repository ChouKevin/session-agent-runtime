package com.java.system.sessionagent.worker.application;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.worker.configuration.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MessageJobWorker implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageJobWorker.class);
    private final ConversationStore conversationStore;
    private final MessageJobPort messageJobPort;
    private final WorkerProperties properties;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workerExecutor;
    private final String workerId;
    private final ConversationTelemetry telemetry;
    private final Map<SessionId, Object> occupiedSessions = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public MessageJobWorker(
            ConversationStore conversationStore,
            MessageJobPort messageJobPort,
            WorkerProperties properties,
            Clock clock,
            ScheduledExecutorService scheduler,
            String workerId) {
        this(conversationStore, messageJobPort, properties, clock, scheduler,
                Executors.newFixedThreadPool(properties.concurrency()), workerId, new NoOpConversationTelemetry());
    }

    public MessageJobWorker(
            ConversationStore conversationStore,
            MessageJobPort messageJobPort,
            WorkerProperties properties,
            Clock clock,
            ScheduledExecutorService scheduler,
            String workerId,
            ConversationTelemetry telemetry) {
        this(conversationStore, messageJobPort, properties, clock, scheduler,
                Executors.newFixedThreadPool(properties.concurrency()), workerId, telemetry);
    }

    public MessageJobWorker(
            ConversationStore conversationStore,
            MessageJobPort messageJobPort,
            WorkerProperties properties,
            Clock clock,
            ScheduledExecutorService scheduler,
            ExecutorService workerExecutor,
            String workerId,
            ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.messageJobPort = Objects.requireNonNull(messageJobPort, "Message job port must not be null");
        this.properties = Objects.requireNonNull(properties, "Worker properties must not be null");
        Objects.requireNonNull(clock, "Clock must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler must not be null");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "Worker executor must not be null");
        Assert.hasText(workerId, "Worker ID must not be blank");
        this.workerId = workerId;
        this.telemetry = Objects.requireNonNull(telemetry, "Conversation telemetry must not be null");
    }

    public boolean poll() {
        return poll(false);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (int index = 0; index < properties.concurrency(); index++) {
            workerExecutor.execute(this::runLoop);
        }
    }

    @Override
    public void stop() {
        synchronized (occupiedSessions) {
            running.set(false);
        }
        workerExecutor.shutdown();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!poll(true)) {
                    waitForPollDelay();
                }
            } catch (RuntimeException exception) {
                waitForPollDelay();
            }
        }
    }

    private void waitForPollDelay() {
        try {
            TimeUnit.NANOSECONDS.sleep(properties.pollDelay().toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean poll(boolean requireRunning) {
        Optional<ActiveClaim> claim = claimAndRegister(requireRunning);
        if (claim.isEmpty()) {
            telemetry.job("EMPTY");
            return false;
        }

        ActiveClaim activeClaim = claim.orElseThrow();
        try {
            MessageWorkClaim currentClaim = activeClaim.claim();
            telemetry.job("CLAIMED");
            LOGGER.atInfo().addKeyValue("event", "message_job_claimed")
                    .addKeyValue("messageJobId", currentClaim.messageJobId().value())
                    .addKeyValue("sessionId", currentClaim.sessionId().value())
                    .addKeyValue("component", "MESSAGE_JOB_WORKER").log("runtime_lifecycle");
            AtomicBoolean stillOwned = new AtomicBoolean(true);
            long renewalNanos = properties.renewalInterval().toNanos();
            ScheduledFuture<?> renewal = scheduler.scheduleAtFixedRate(
                    () -> renew(currentClaim, stillOwned),
                    renewalNanos,
                    renewalNanos,
                    TimeUnit.NANOSECONDS);
            try {
                WorkGuard guard = workGuard(currentClaim, stillOwned);
                MessageJobProcessingResult result = Objects.requireNonNull(
                        messageJobPort.process(currentClaim, guard), "Message job processing result must not be null");
                logProcessingResult(currentClaim, result);
                return true;
            } catch (RuntimeException exception) {
                telemetry.job("FAILED");
                LOGGER.atInfo().addKeyValue("event", "message_job_failed")
                        .addKeyValue("messageJobId", currentClaim.messageJobId().value())
                        .addKeyValue("sessionId", currentClaim.sessionId().value())
                        .addKeyValue("failureCategory", "UNEXPECTED").log("runtime_lifecycle");
                throw exception;
            } finally {
                renewal.cancel(false);
            }
        } finally {
            release(activeClaim);
        }
    }

    private Optional<ActiveClaim> claimAndRegister(boolean requireRunning) {
        synchronized (occupiedSessions) {
            if (requireRunning && !running.get()) {
                return Optional.empty();
            }
            Optional<MessageWorkClaim> claimed = conversationStore.claimNext(
                    workerId, properties.claimDuration(), Set.copyOf(occupiedSessions.keySet()));
            if (claimed.isEmpty()) {
                return Optional.empty();
            }
            MessageWorkClaim claim = claimed.orElseThrow();
            if (occupiedSessions.containsKey(claim.sessionId())) {
                LOGGER.atWarn().addKeyValue("event", "message_job_claim_rejected")
                        .addKeyValue("messageJobId", claim.messageJobId().value())
                        .addKeyValue("sessionId", claim.sessionId().value())
                        .addKeyValue("outcome", "SESSION_OCCUPIED").log("runtime_lifecycle");
                return Optional.empty();
            }
            Object localOwner = new Object();
            occupiedSessions.put(claim.sessionId(), localOwner);
            return Optional.of(new ActiveClaim(claim, localOwner));
        }
    }

    private void release(ActiveClaim activeClaim) {
        synchronized (occupiedSessions) {
            occupiedSessions.remove(activeClaim.claim().sessionId(), activeClaim.localOwner());
        }
    }

    private void logProcessingResult(MessageWorkClaim claim, MessageJobProcessingResult result) {
        switch (result) {
            case COMPLETED -> {
                telemetry.job("COMPLETED");
                logJobResult("message_job_completed", claim, "DONE");
            }
            case RETRY_SCHEDULED -> {
                telemetry.job("RETRY_SCHEDULED");
                logJobResult("message_job_processing_finished", claim, "RETRY_SCHEDULED");
            }
            case OWNERSHIP_LOST -> {
                telemetry.job("OWNERSHIP_LOST");
                logJobResult("message_job_ownership_lost", claim, "OWNERSHIP_LOST");
            }
            case STATE_UNCONFIRMED -> {
                telemetry.job("STATE_UNCONFIRMED");
                logJobResult("message_job_state_unconfirmed", claim, "STATE_UNCONFIRMED");
            }
        }
    }

    private static void logJobResult(String event, MessageWorkClaim claim, String outcome) {
        LOGGER.atInfo().addKeyValue("event", event)
                .addKeyValue("messageJobId", claim.messageJobId().value())
                .addKeyValue("sessionId", claim.sessionId().value())
                .addKeyValue("outcome", outcome).log("runtime_lifecycle");
    }

    private void renew(MessageWorkClaim claim, AtomicBoolean stillOwned) {
        if (!stillOwned.get()) {
            return;
        }
        try {
            if (!conversationStore.extendClaim(claim, properties.claimDuration())) {
                stillOwned.set(false);
                LOGGER.atInfo().addKeyValue("event", "message_job_recovery")
                        .addKeyValue("messageJobId", claim.messageJobId().value())
                        .addKeyValue("sessionId", claim.sessionId().value())
                        .addKeyValue("outcome", "OWNERSHIP_LOST").log("runtime_lifecycle");
            }
        } catch (RuntimeException exception) {
            stillOwned.set(false);
            LOGGER.atInfo().addKeyValue("event", "message_job_recovery")
                    .addKeyValue("messageJobId", claim.messageJobId().value())
                    .addKeyValue("sessionId", claim.sessionId().value())
                    .addKeyValue("failureCategory", "STORAGE").log("runtime_lifecycle");
        }
    }

    private WorkGuard workGuard(MessageWorkClaim claim, AtomicBoolean stillOwned) {
        return new WorkGuard() {
            @Override
            public boolean stillOwned() {
                return stillOwned.get();
            }

            @Override
            public boolean confirmOwnership() {
                if (!stillOwned.get()) {
                    return false;
                }
                try {
                    boolean confirmed = conversationStore.extendClaim(claim, properties.claimDuration());
                    if (!confirmed) {
                        stillOwned.set(false);
                    }
                    return confirmed;
                } catch (RuntimeException exception) {
                    stillOwned.set(false);
                    return false;
                }
            }
        };
    }

    private record ActiveClaim(MessageWorkClaim claim, Object localOwner) {
    }
}
