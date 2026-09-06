package com.java.system.sessionagent.worker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobWorkerTest {

    @Test
    void reports_empty_when_no_claim_is_available() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.claimNext(any(String.class), any(Duration.class))).thenReturn(Optional.empty());
        MessageJobWorker worker = worker(store, mock(MessageJobPort.class), mock(ScheduledExecutorService.class));

        assertThat(worker.poll()).isFalse();
    }

    @Test
    void rethrows_processing_failure_after_claiming_work() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"), "worker", 1,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:01:00Z"));
        when(store.claimNext(any(String.class), any(Duration.class))).thenReturn(Optional.of(claim));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler).scheduleAtFixedRate(any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
        MessageJobPort jobs = mock(MessageJobPort.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("failed")).when(jobs).process(any(), any());

        assertThatThrownBy(() -> worker(store, jobs, scheduler).poll()).isInstanceOf(IllegalStateException.class)
                .hasMessage("failed");
        verify(renewal).cancel(false);
    }

    @Test
    void reports_lost_ownership_to_processing_when_the_heartbeat_is_rejected() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"), "worker", 1,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:01:00Z"));
        when(store.claimNext(any(String.class), any(Duration.class))).thenReturn(Optional.of(claim));
        when(store.extendClaim(claim, Duration.ofSeconds(30))).thenReturn(false);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        AtomicReference<Runnable> renewalTask = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            renewalTask.set(invocation.getArgument(0));
            return renewal;
        }).when(scheduler).scheduleAtFixedRate(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
        MessageJobPort jobs = mock(MessageJobPort.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            renewalTask.get().run();
            assertThat(invocation.getArgument(1, com.java.system.sessionagent.conversation.port.in.WorkGuard.class).stillOwned()).isFalse();
            return MessageJobProcessingResult.OWNERSHIP_LOST;
        }).when(jobs).process(org.mockito.Mockito.eq(claim), any());
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), com.java.system.sessionagent.conversation.domain.JobStatus.DONE, 1, 1)));
        Logger logger = (Logger) LoggerFactory.getLogger(MessageJobWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThat(worker(store, jobs, scheduler).poll()).isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        verify(store).extendClaim(claim, Duration.ofSeconds(30));
        verify(store, never()).readJob(claim.messageJobId());
        verify(renewal).cancel(false);
        assertThat(appender.list).extracting(event -> keyValues(event).get("event"))
                .contains("message_job_ownership_lost")
                .doesNotContain("message_job_completed");
    }

    @Test
    void reports_retry_scheduled_from_the_single_persisted_state_snapshot_without_a_false_completion() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"), "worker", 1,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:01:00Z"));
        when(store.claimNext(any(String.class), any(Duration.class))).thenReturn(Optional.of(claim));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler).scheduleAtFixedRate(any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
        MessageJobPort jobs = mock(MessageJobPort.class);
        when(jobs.process(org.mockito.Mockito.eq(claim), any())).thenReturn(MessageJobProcessingResult.RETRY_SCHEDULED);
        MessageJobWorker worker = worker(store, jobs, scheduler);
        Logger logger = (Logger) LoggerFactory.getLogger(MessageJobWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(worker.poll()).isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).extracting(event -> keyValues(event).get("event"))
                .contains("message_job_claimed", "message_job_processing_finished")
                .doesNotContain("message_job_completed");
        ILoggingEvent result = appender.list.stream()
                .filter(event -> "message_job_processing_finished".equals(keyValues(event).get("event")))
                .findFirst().orElseThrow();
        assertThat(keyValues(result)).containsEntry("outcome", "RETRY_SCHEDULED")
                .containsEntry("sessionId", "session")
                .containsEntry("messageJobId", "job");
        verify(store, never()).readJob(claim.messageJobId());
        verify(renewal).cancel(false);
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static MessageJobWorker worker(ConversationStore store, MessageJobPort jobs, ScheduledExecutorService scheduler) {
        return new MessageJobWorker(store, jobs, new WorkerProperties(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), scheduler, "worker");
    }
}
