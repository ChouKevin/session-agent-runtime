package com.java.system.sessionagent.worker.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.in.WorkGuard;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.worker.configuration.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.empty());
        MessageJobWorker worker = worker(store, mock(MessageJobPort.class), mock(ScheduledExecutorService.class));

        assertThat(worker.poll()).isFalse();
    }

    @Test
    void rethrows_processing_failure_after_claiming_work() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"),
                new ModelSelection("test", "v1:test"), "worker", 1,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:01:00Z"));
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.of(claim));
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
    void does_not_start_a_second_local_workflow_for_an_occupied_session() throws Exception {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim firstClaim = claim("first", "session");
        MessageWorkClaim secondClaim = claim("second", "session");
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.of(firstClaim), Optional.of(secondClaim));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler).scheduleAtFixedRate(any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger activeWorkflows = new AtomicInteger();
        AtomicInteger maximumActiveWorkflows = new AtomicInteger();
        MessageJobPort jobs = (claim, guard) -> {
            int active = activeWorkflows.incrementAndGet();
            maximumActiveWorkflows.accumulateAndGet(active, Math::max);
            if (claim.messageJobId().equals(firstClaim.messageJobId())) {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            activeWorkflows.decrementAndGet();
            return MessageJobProcessingResult.COMPLETED;
        };
        MessageJobWorker worker = worker(store, jobs, scheduler);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(worker::poll);
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = executor.submit(worker::poll);
            assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(maximumActiveWorkflows).hasValue(1);

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void serializes_claiming_and_registration_before_an_expired_lease_can_be_reclaimed() throws Exception {
        MessageWorkClaim staleClaim = claim("stale", "session");
        ConversationStore store = mock(ConversationStore.class);
        AtomicInteger claimAttempts = new AtomicInteger();
        CountDownLatch firstDatabaseClaimed = new CountDownLatch(1);
        CountDownLatch releaseFirstClaim = new CountDownLatch(1);
        CountDownLatch secondDatabaseClaimed = new CountDownLatch(1);
        AtomicBoolean secondClaimExcludedSession = new AtomicBoolean();
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenAnswer(invocation -> {
                    Set<SessionId> excludedSessions = invocation.getArgument(2);
                    if (claimAttempts.getAndIncrement() == 0) {
                        firstDatabaseClaimed.countDown();
                        try {
                            releaseFirstClaim.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                        return Optional.of(staleClaim);
                    }
                    secondClaimExcludedSession.set(excludedSessions.contains(staleClaim.sessionId()));
                    secondDatabaseClaimed.countDown();
                    return Optional.empty();
                });
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler).scheduleAtFixedRate(any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        MessageJobPort jobs = (claim, guard) -> {
            processingStarted.countDown();
            try {
                releaseProcessing.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return MessageJobProcessingResult.COMPLETED;
        };
        MessageJobWorker worker = worker(store, jobs, scheduler);
        ExecutorService pollingExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pollingExecutor.submit(worker::poll);
            assertThat(firstDatabaseClaimed.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = pollingExecutor.submit(worker::poll);
            assertThat(secondDatabaseClaimed.await(250, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirstClaim.countDown();
            assertThat(processingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondDatabaseClaimed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondClaimExcludedSession).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();

            releaseProcessing.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirstClaim.countDown();
            releaseProcessing.countDown();
            pollingExecutor.shutdownNow();
        }
    }

    @Test
    void keeps_shutdown_outside_a_blocked_database_claim() throws Exception {
        ConversationStore store = mock(ConversationStore.class);
        CountDownLatch databaseClaimStarted = new CountDownLatch(1);
        CountDownLatch releaseDatabaseClaim = new CountDownLatch(1);
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenAnswer(invocation -> {
                    databaseClaimStarted.countDown();
                    try {
                        releaseDatabaseClaim.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return Optional.empty();
                });
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService stopExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch shutdownCallback = new CountDownLatch(1);
        WorkerProperties properties = new WorkerProperties(Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofMillis(10), 1);
        MessageJobWorker worker = new MessageJobWorker(store, mock(MessageJobPort.class), properties,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), scheduler, workerExecutor, "worker", new NoOpConversationTelemetry());
        try {
            worker.start();
            assertThat(databaseClaimStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> stopping = stopExecutor.submit(() -> worker.stop(shutdownCallback::countDown));
            stopping.get(250, TimeUnit.MILLISECONDS);
            assertThat(worker.isRunning()).isFalse();

            releaseDatabaseClaim.countDown();
            assertThat(shutdownCallback.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workerExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseDatabaseClaim.countDown();
            worker.stop();
            scheduler.shutdownNow();
            workerExecutor.shutdownNow();
            stopExecutor.shutdownNow();
        }
    }

    @Test
    void releases_local_occupancy_when_renewal_scheduling_fails() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim firstClaim = claim("first", "session");
        MessageWorkClaim secondClaim = claim("second", "session");
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.of(firstClaim), Optional.of(secondClaim));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doThrow(new RejectedExecutionException("scheduler stopped")).doReturn(renewal)
                .when(scheduler).scheduleAtFixedRate(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(), any());
        MessageJobPort jobs = mock(MessageJobPort.class);
        when(jobs.process(org.mockito.Mockito.eq(secondClaim), any())).thenReturn(MessageJobProcessingResult.COMPLETED);
        MessageJobWorker worker = worker(store, jobs, scheduler);

        assertThatThrownBy(worker::poll).isInstanceOf(RejectedExecutionException.class);
        assertThat(worker.poll()).isTrue();

        verify(jobs).process(org.mockito.Mockito.eq(secondClaim), any());
        verify(renewal).cancel(false);
    }

    @Test
    void starts_configured_loops_and_recovers_a_failed_loop_before_shutdown_finishes_its_active_work() throws Exception {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim firstClaim = claim("first", "first-session");
        MessageWorkClaim secondClaim = claim("second", "second-session");
        MessageWorkClaim thirdClaim = claim("third", "third-session");
        MessageWorkClaim fourthClaim = claim("fourth", "fourth-session");
        MessageWorkClaim recoveredClaim = claim("recovered", "recovered-session");
        AtomicLong claimAttempts = new AtomicLong();
        AtomicLong failedLoopId = new AtomicLong(-1L);
        AtomicBoolean recoveredClaimIssued = new AtomicBoolean();
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenAnswer(invocation -> {
                    long claimAttempt = claimAttempts.getAndIncrement();
                    if (claimAttempt < 4) {
                        return Optional.of(List.of(firstClaim, secondClaim, thirdClaim, fourthClaim).get((int) claimAttempt));
                    }
                    if (failedLoopId.get() == Thread.currentThread().threadId() && recoveredClaimIssued.compareAndSet(false, true)) {
                        return Optional.of(recoveredClaim);
                    }
                    return Optional.empty();
                });
        CountDownLatch allStarted = new CountDownLatch(4);
        CountDownLatch renewalObserved = new CountDownLatch(4);
        CountDownLatch releaseInitialWork = new CountDownLatch(1);
        CountDownLatch recoveredStarted = new CountDownLatch(1);
        CountDownLatch releaseRecoveredWork = new CountDownLatch(1);
        CountDownLatch shutdownCallback = new CountDownLatch(1);
        AtomicInteger activeWorkflows = new AtomicInteger();
        AtomicInteger maximumActiveWorkflows = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();
        MessageJobPort jobs = (claim, guard) -> {
            int active = activeWorkflows.incrementAndGet();
            maximumActiveWorkflows.accumulateAndGet(active, Math::max);
            processed.incrementAndGet();
            try {
                if (claim.messageJobId().equals(recoveredClaim.messageJobId())) {
                    assertThat(Thread.currentThread().threadId()).isEqualTo(failedLoopId.get());
                    recoveredStarted.countDown();
                    releaseRecoveredWork.await();
                    return MessageJobProcessingResult.COMPLETED;
                }
                allStarted.countDown();
                releaseInitialWork.await();
                if (claim.messageJobId().equals(firstClaim.messageJobId())) {
                    failedLoopId.set(Thread.currentThread().threadId());
                    throw new IllegalStateException("processing failed once");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                activeWorkflows.decrementAndGet();
            }
            return MessageJobProcessingResult.COMPLETED;
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workerExecutor = Executors.newFixedThreadPool(4);
        WorkerProperties properties = new WorkerProperties(Duration.ofMillis(100), Duration.ofMillis(10), Duration.ofMillis(10), 4);
        MessageJobWorker worker = new MessageJobWorker(store, jobs, properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                scheduler, workerExecutor, "worker", new NoOpConversationTelemetry());
        try {
            org.mockito.Mockito.doAnswer(invocation -> {
                renewalObserved.countDown();
                return true;
            }).when(store).extendClaim(any(MessageWorkClaim.class), any(Duration.class));

            worker.start();

            assertThat(allStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(renewalObserved.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maximumActiveWorkflows).hasValue(4);
            assertThat(worker.isRunning()).isTrue();

            releaseInitialWork.countDown();
            assertThat(recoveredStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(recoveredClaimIssued).isTrue();

            worker.stop(shutdownCallback::countDown);
            releaseRecoveredWork.countDown();
            assertThat(shutdownCallback.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workerExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(processed).hasValue(5);
            assertThat(worker.isRunning()).isFalse();
        } finally {
            releaseInitialWork.countDown();
            releaseRecoveredWork.countDown();
            worker.stop();
            scheduler.shutdownNow();
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void hands_off_a_blocked_workflow_after_bounded_shutdown_without_claiming_more_work() throws Exception {
        MessageWorkClaim activeClaim = claim("active", "session");
        AtomicInteger claims = new AtomicInteger();
        ConversationStore store = mock(ConversationStore.class);
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenAnswer(invocation -> claims.getAndIncrement() == 0 ? Optional.of(activeClaim) : Optional.empty());
        CountDownLatch activeStarted = new CountDownLatch(1);
        CountDownLatch releaseActive = new CountDownLatch(1);
        CountDownLatch shutdownCallback = new CountDownLatch(1);
        AtomicReference<WorkGuard> activeGuard = new AtomicReference<>();
        MessageJobPort jobs = (claim, guard) -> {
            activeGuard.set(guard);
            activeStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseActive.await(25, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    // Simulates an external call that ignores interruption until it returns.
                }
            }
            return MessageJobProcessingResult.COMPLETED;
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workerExecutor = Executors.newFixedThreadPool(1);
        WorkerProperties properties = new WorkerProperties(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(10), 1);
        MessageJobWorker worker = new MessageJobWorker(store, jobs, properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                scheduler, workerExecutor, "worker", new NoOpConversationTelemetry());
        try {
            worker.start();
            assertThat(activeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            worker.stop(shutdownCallback::countDown);

            assertThat(shutdownCallback.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(activeGuard.get()).isNotNull();
            assertThat(activeGuard.get().stillOwned()).isFalse();
            assertThat(shutdownCallback.await(7, TimeUnit.SECONDS)).isTrue();
            assertThat(claims).hasValue(1);
            assertThat(workerExecutor.isTerminated()).isFalse();

            releaseActive.countDown();
            assertThat(workerExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseActive.countDown();
            worker.stop();
            scheduler.shutdownNow();
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void interrupts_and_retires_a_running_renewal_during_shutdown_handoff() throws Exception {
        MessageWorkClaim activeClaim = claim("active", "session");
        ConversationStore store = mock(ConversationStore.class);
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.of(activeClaim), Optional.empty());
        CountDownLatch workflowStarted = new CountDownLatch(1);
        CountDownLatch releaseWorkflow = new CountDownLatch(1);
        CountDownLatch renewalStarted = new CountDownLatch(1);
        CountDownLatch blockRenewal = new CountDownLatch(1);
        CountDownLatch renewalInterrupted = new CountDownLatch(1);
        CountDownLatch shutdownCallback = new CountDownLatch(1);
        AtomicReference<WorkGuard> activeGuard = new AtomicReference<>();
        when(store.extendClaim(activeClaim, Duration.ofSeconds(1))).thenAnswer(invocation -> {
            renewalStarted.countDown();
            try {
                blockRenewal.await();
            } catch (InterruptedException exception) {
                renewalInterrupted.countDown();
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        });
        MessageJobPort jobs = (claim, guard) -> {
            activeGuard.set(guard);
            workflowStarted.countDown();
            try {
                releaseWorkflow.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return MessageJobProcessingResult.COMPLETED;
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        WorkerProperties properties = new WorkerProperties(Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofMillis(10), 1);
        MessageJobWorker worker = new MessageJobWorker(store, jobs, properties, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                scheduler, workerExecutor, "worker", new NoOpConversationTelemetry());
        try {
            worker.start();
            assertThat(workflowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(renewalStarted.await(5, TimeUnit.SECONDS)).isTrue();

            worker.stop(shutdownCallback::countDown);

            assertThat(renewalInterrupted.await(250, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(activeGuard.get().stillOwned()).isFalse();
            releaseWorkflow.countDown();
            assertThat(shutdownCallback.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(workerExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseWorkflow.countDown();
            worker.stop();
            scheduler.shutdownNow();
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void does_not_grant_ownership_when_shutdown_handoff_races_confirmation() throws Exception {
        MessageWorkClaim claim = claim("active", "session");
        ConversationStore store = mock(ConversationStore.class);
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.of(claim));
        CountDownLatch confirmationStarted = new CountDownLatch(1);
        CountDownLatch releaseConfirmation = new CountDownLatch(1);
        when(store.extendClaim(claim, Duration.ofSeconds(30))).thenAnswer(invocation -> {
            confirmationStarted.countDown();
            try {
                releaseConfirmation.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return true;
        });
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler).scheduleAtFixedRate(any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
        AtomicBoolean confirmation = new AtomicBoolean(true);
        MessageJobPort jobs = (messageClaim, guard) -> {
            confirmation.set(guard.confirmOwnership());
            return MessageJobProcessingResult.COMPLETED;
        };
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService pollingExecutor = Executors.newSingleThreadExecutor();
        MessageJobWorker worker = new MessageJobWorker(store, jobs, new WorkerProperties(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                scheduler, workerExecutor, "worker", new NoOpConversationTelemetry());
        try {
            Future<Boolean> poll = pollingExecutor.submit(worker::poll);
            assertThat(confirmationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            worker.stop();
            releaseConfirmation.countDown();

            assertThat(poll.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(confirmation).isFalse();
        } finally {
            releaseConfirmation.countDown();
            worker.stop();
            pollingExecutor.shutdownNow();
            workerExecutor.shutdownNow();
        }
    }

    @Test
    void reports_lost_ownership_to_processing_when_the_heartbeat_is_rejected() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"),
                new ModelSelection("test", "v1:test"), "worker", 1,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:01:00Z"));
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.of(claim));
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

    @ParameterizedTest
    @CsvSource({
            "RETRY_SCHEDULED, message_job_processing_finished, RETRY_SCHEDULED, message_job_completed",
            "COMPLETED, message_job_completed, DONE, message_job_processing_finished"
    })
    void reports_the_processing_outcome_without_rereading_mutable_job_state(
            MessageJobProcessingResult processingResult, String expectedEvent, String expectedOutcome, String unexpectedEvent) {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"),
                new ModelSelection("test", "v1:test"), "worker", 1,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:01:00Z"));
        when(store.claimNext(any(String.class), any(Duration.class), org.mockito.ArgumentMatchers.<Set<SessionId>>any()))
                .thenReturn(Optional.of(claim));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler).scheduleAtFixedRate(any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any());
        MessageJobPort jobs = mock(MessageJobPort.class);
        when(jobs.process(org.mockito.Mockito.eq(claim), any())).thenReturn(processingResult);
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
                .contains("message_job_claimed", expectedEvent)
                .doesNotContain(unexpectedEvent);
        ILoggingEvent result = appender.list.stream()
                .filter(event -> expectedEvent.equals(keyValues(event).get("event")))
                .findFirst().orElseThrow();
        assertThat(keyValues(result)).containsEntry("outcome", expectedOutcome)
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

    private static MessageWorkClaim claim(String jobId, String sessionId) {
        return new MessageWorkClaim(new MessageJobId(jobId), new SessionId(sessionId),
                new ModelSelection("test", "v1:test"), "worker", 1,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:01:00Z"));
    }
}
