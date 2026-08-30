package com.java.system.sessionagent.worker;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-16T02:00:00Z");

    @Test
    void usesTheRequiredWorkerPropertyDefaultsAndRejectsUnsafeRenewalIntervals() {
        WorkerProperties properties = new WorkerProperties();

        assertThat(properties.claimDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.renewalInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkerProperties(Duration.ofSeconds(30), Duration.ofSeconds(15)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new WorkerProperties(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofNanos(1)));
    }

    @Test
    void schedulesASubMillisecondRenewalWithoutTruncatingItsPeriod() {
        MessageWorkClaim claim = claim();
        RecordingStore store = new RecordingStore(Optional.of(claim), () -> true);
        ControllableScheduler scheduler = new ControllableScheduler();
        MessageJobPort port = (currentClaim, guard) -> { };
        MessageJobWorker worker = new MessageJobWorker(
                store,
                port,
                new WorkerProperties(Duration.ofSeconds(1), Duration.ofNanos(1)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                scheduler,
                "worker-1");

        assertThat(worker.poll()).isTrue();
        assertThat(scheduler.period).isEqualTo(1);
        assertThat(scheduler.unit).isEqualTo(TimeUnit.NANOSECONDS);
    }

    @Test
    void doesNotProcessWhenNoJobWasClaimed() {
        RecordingStore store = new RecordingStore(Optional.empty(), () -> true);
        AtomicInteger processCalls = new AtomicInteger();
        MessageJobPort port = (claim, guard) -> processCalls.incrementAndGet();
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        MessageJobWorker worker = new MessageJobWorker(store, port, new WorkerProperties(), Clock.fixed(NOW, ZoneOffset.UTC),
                new ControllableScheduler(), "worker-1", telemetry);

        assertThat(worker.poll()).isFalse();
        assertThat(processCalls).hasValue(0);
        verify(telemetry).job("EMPTY");
    }

    @Test
    void recordsFailedJobProcessingBeforeRethrowingTheOriginalFailure() {
        RecordingStore store = new RecordingStore(Optional.of(claim()), () -> true);
        ConversationTelemetry telemetry = mock(ConversationTelemetry.class);
        MessageJobWorker worker = new MessageJobWorker(store, (claim, guard) -> {
            throw new IllegalStateException("unexpected");
        }, new WorkerProperties(), Clock.fixed(NOW, ZoneOffset.UTC), new ControllableScheduler(), "worker-1", telemetry);

        assertThatThrownBy(worker::poll).isInstanceOf(IllegalStateException.class);

        verify(telemetry).job("CLAIMED");
        verify(telemetry).job("FAILED");
    }

    @Test
    void renewsDuringBlockedProcessingCancelsTheTaskAndPreventsPostCallCommitAfterOwnershipLoss() throws Exception {
        MessageWorkClaim claim = claim();
        RecordingStore store = new RecordingStore(Optional.of(claim), () -> false);
        ControllableScheduler scheduler = new ControllableScheduler();
        CountDownLatch enteredProcess = new CountDownLatch(1);
        CountDownLatch releaseProcess = new CountDownLatch(1);
        AtomicInteger postCallCommitAttempts = new AtomicInteger();
        AtomicReference<WorkGuard> guardReference = new AtomicReference<>();
        MessageJobPort port = (currentClaim, guard) -> {
            guardReference.set(guard);
            enteredProcess.countDown();
            try {
                releaseProcess.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while processing a message job", exception);
            }
            if (guard.stillOwned()) {
                postCallCommitAttempts.incrementAndGet();
            }
        };
        MessageJobWorker worker = worker(store, port, scheduler);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = executor.submit(worker::poll);
            enteredProcess.await();

            scheduler.runRenewal();
            releaseProcess.countDown();

            assertThat(result.get()).isTrue();
            assertThat(store.extendCalls).isEqualTo(1);
            assertThat(guardReference.get().stillOwned()).isFalse();
            assertThat(postCallCommitAttempts).hasValue(0);
            assertThat(scheduler.cancelled).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void treatsARenewalExceptionAsLostOwnership() throws Exception {
        MessageWorkClaim claim = claim();
        RecordingStore store = new RecordingStore(Optional.of(claim), () -> {
            throw new IllegalStateException("renewal failed");
        });
        ControllableScheduler scheduler = new ControllableScheduler();
        CountDownLatch enteredProcess = new CountDownLatch(1);
        CountDownLatch releaseProcess = new CountDownLatch(1);
        AtomicInteger postCallCommitAttempts = new AtomicInteger();
        MessageJobPort port = (currentClaim, guard) -> {
            enteredProcess.countDown();
            try {
                releaseProcess.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while processing a message job", exception);
            }
            if (guard.stillOwned()) {
                postCallCommitAttempts.incrementAndGet();
            }
        };
        MessageJobWorker worker = worker(store, port, scheduler);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = executor.submit(worker::poll);
            enteredProcess.await();

            scheduler.runRenewal();
            releaseProcess.countDown();

            assertThat(result.get()).isTrue();
            assertThat(postCallCommitAttempts).hasValue(0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retainsOwnershipWhenAHeartbeatSucceedsDuringBlockedProcessing() throws Exception {
        MessageWorkClaim claim = claim();
        RecordingStore store = new RecordingStore(Optional.of(claim), () -> true);
        ControllableScheduler scheduler = new ControllableScheduler();
        CountDownLatch enteredProcess = new CountDownLatch(1);
        CountDownLatch releaseProcess = new CountDownLatch(1);
        AtomicInteger postCallCommitAttempts = new AtomicInteger();
        AtomicReference<WorkGuard> guardReference = new AtomicReference<>();
        MessageJobPort port = (currentClaim, guard) -> {
            guardReference.set(guard);
            enteredProcess.countDown();
            try {
                releaseProcess.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while processing a message job", exception);
            }
            if (guard.stillOwned()) {
                postCallCommitAttempts.incrementAndGet();
            }
        };
        MessageJobWorker worker = new MessageJobWorker(
                store,
                port,
                new WorkerProperties(),
                new ProgressingClock(NOW),
                scheduler,
                "worker-1");
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = executor.submit(worker::poll);
            enteredProcess.await();

            scheduler.runRenewal();
            releaseProcess.countDown();

            assertThat(result.get()).isTrue();
            assertThat(store.lastLeaseDuration).isEqualTo(java.time.Duration.ofSeconds(30));
            assertThat(guardReference.get().stillOwned()).isTrue();
            assertThat(postCallCommitAttempts).hasValue(1);
            assertThat(scheduler.cancelled).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private MessageJobWorker worker(ConversationStore store, MessageJobPort port, ScheduledExecutorService scheduler) {
        return new MessageJobWorker(
                store,
                port,
                new WorkerProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                scheduler,
                "worker-1");
    }

    private MessageWorkClaim claim() {
        return new MessageWorkClaim(
                new MessageJobId("job-1"), new SessionId("session-1"), "worker-1", 1, NOW, NOW.plusSeconds(30));
    }

    private static final class RecordingStore implements ConversationStore {

        private final Optional<MessageWorkClaim> nextClaim;
        private final Supplier<Boolean> renewalAction;
        private int extendCalls;
        private java.time.Duration lastLeaseDuration;

        private RecordingStore(Optional<MessageWorkClaim> nextClaim, Supplier<Boolean> renewalAction) {
            this.nextClaim = nextClaim;
            this.renewalAction = renewalAction;
        }

        @Override
        public Optional<MessageWorkClaim> claimNext(String workerId, java.time.Duration leaseDuration) {
            return nextClaim;
        }

        @Override
        public boolean extendClaim(MessageWorkClaim claim, java.time.Duration leaseDuration) {
            extendCalls++;
            lastLeaseDuration = leaseDuration;
            return renewalAction.get();
        }

        @Override public com.java.system.sessionagent.conversation.domain.MessageReceipt receive(com.java.system.sessionagent.conversation.domain.IncomingMessage message) { throw new UnsupportedOperationException(); }
        @Override public List<com.java.system.sessionagent.conversation.domain.SessionMessage> loadHistory(SessionId sessionId) { throw new UnsupportedOperationException(); }
        @Override public List<com.java.system.sessionagent.conversation.domain.SessionMessage> loadHistory(SessionId sessionId, MessageJobId messageJobId) { throw new UnsupportedOperationException(); }
        @Override public OptionalInt reserveModelCall(MessageWorkClaim claim, Instant now) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.ToolMessage appendTool(MessageWorkClaim claim, com.java.system.sessionagent.conversation.domain.ResultId resultId, String modelCallId, String modelContext, ToolData toolData, Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.FeedbackMessage appendFeedback(MessageWorkClaim claim, String code, String message, boolean terminal, Optional<String> modelCallId, Optional<String> toolName, Optional<String> rejectedArguments, Optional<String> modelContext, Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.AssistantMessage appendAssistant(MessageWorkClaim claim, String message, Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public boolean scheduleRetry(MessageWorkClaim claim, java.time.Duration retryDelay) { throw new UnsupportedOperationException(); }
        @Override public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) { throw new UnsupportedOperationException(); }
        @Override public Optional<ResultProjection> readResult(com.java.system.sessionagent.conversation.domain.ResultId resultId) { throw new UnsupportedOperationException(); }
    }

    private static final class ControllableScheduler extends AbstractExecutorService implements ScheduledExecutorService {

        private Runnable renewal;
        private boolean cancelled;
        private long period;
        private TimeUnit unit;

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            renewal = command;
            this.period = period;
            this.unit = unit;
            return new ControlledFuture(this);
        }

        void runRenewal() {
            renewal.run();
        }

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public <V> ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
    }

    private static final class ProgressingClock extends Clock {

        private Instant current;

        private ProgressingClock(Instant initial) {
            this.current = initial;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant returned = current;
            current = current.plusSeconds(1);
            return returned;
        }
    }

    private static final class ControlledFuture implements ScheduledFuture<Object> {

        private final ControllableScheduler scheduler;

        private ControlledFuture(ControllableScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) { scheduler.cancelled = true; return true; }
        @Override public boolean isCancelled() { return scheduler.cancelled; }
        @Override public boolean isDone() { return scheduler.cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(java.util.concurrent.Delayed other) { return 0; }
    }
}
