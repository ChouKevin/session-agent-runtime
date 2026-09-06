package com.java.system.sessionagent.worker;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.Assert;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MessageJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageJobWorker.class);
    private final ConversationStore conversationStore;
    private final MessageJobPort messageJobPort;
    private final WorkerProperties properties;
    private final ScheduledExecutorService scheduler;
    private final String workerId;
    private final ConversationTelemetry telemetry;

    public MessageJobWorker(
            ConversationStore conversationStore,
            MessageJobPort messageJobPort,
            WorkerProperties properties,
            Clock clock,
            ScheduledExecutorService scheduler,
            String workerId) {
        this(conversationStore, messageJobPort, properties, clock, scheduler, workerId, new NoOpConversationTelemetry());
    }

    public MessageJobWorker(
            ConversationStore conversationStore,
            MessageJobPort messageJobPort,
            WorkerProperties properties,
            Clock clock,
            ScheduledExecutorService scheduler,
            String workerId,
            ConversationTelemetry telemetry) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "Conversation store must not be null");
        this.messageJobPort = Objects.requireNonNull(messageJobPort, "Message job port must not be null");
        this.properties = Objects.requireNonNull(properties, "Worker properties must not be null");
        Objects.requireNonNull(clock, "Clock must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler must not be null");
        Assert.hasText(workerId, "Worker ID must not be blank");
        this.workerId = workerId;
        this.telemetry = Objects.requireNonNull(telemetry, "Conversation telemetry must not be null");
    }

    @Scheduled(fixedDelayString = "${session-agent.worker.poll-delay:1s}")
    public boolean poll() {
        Optional<MessageWorkClaim> claim = conversationStore.claimNext(workerId, properties.claimDuration());
        if (claim.isEmpty()) {
            telemetry.job("EMPTY");
            return false;
        }

        MessageWorkClaim currentClaim = claim.orElseThrow();
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
            WorkGuard guard = stillOwned::get;
            messageJobPort.process(currentClaim, guard);
            telemetry.job(stillOwned.get() ? "COMPLETED" : "OWNERSHIP_LOST");
            LOGGER.atInfo().addKeyValue("event", stillOwned.get() ? "message_job_completed" : "message_job_ownership_lost")
                    .addKeyValue("messageJobId", currentClaim.messageJobId().value())
                    .addKeyValue("sessionId", currentClaim.sessionId().value()).log("runtime_lifecycle");
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
}
