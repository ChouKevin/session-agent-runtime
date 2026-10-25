package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.JobControl;

import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobPhase;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.tool.port.ArtifactReceipt;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import com.java.system.sessionagent.tool.port.ToolExecutionContext;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobRecoveryTest {
    @Test
    void preserves_completed_tool_results_when_storage_failure_schedules_retry() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any(Instant.class)))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty())));
        when(store.appendAndScheduleRetry(eq(claim), any(), any(), eq(JobFailure.STORAGE_UNAVAILABLE), any()))
                .thenReturn(JobStatus.RETRY);
        AtomicBoolean completedFirst = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (progress.phase() == JobPhase.TOOL_EXECUTION && completedFirst.getAndSet(false)) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("admission unavailable"));
            }
            return null;
        }).when(store).updateProgress(eq(claim), any());
        ArtifactReceipt artifactReceipt = new ArtifactReceipt(UUID.randomUUID(), UUID.randomUUID(),
                new ToolExecutionContext(UUID.fromString(claim.sessionId().value()), UUID.fromString(claim.messageJobId().value()), 1, 0));
        SkillReadReceipt skillReceipt = SkillReadReceipt.succeeded(UUID.randomUUID(), "skill://review", "skill://review", "a".repeat(64));
        ToolOutput actual = new ToolOutput(false, Map.of("actual", "completed"),
                Optional.of(artifactReceipt), Optional.of(skillReceipt));
        AtomicInteger secondInvocations = new AtomicInteger();
        ToolCatalog tools = () -> new ToolSnapshot(List.of(
                binding("first", arguments -> { completedFirst.set(true); return actual; }),
                binding("second", arguments -> { secondInvocations.incrementAndGet(); return new ToolOutput(false, Map.of()); })));
        ModelContinuation continuation = new ModelContinuation(TEST_ROUTE_ID, "opaque-v1", new byte[] {1, 2});
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return new ModelCallResult(new ModelReply.UseTools(Optional.of("Checking"), List.of(
                    request("one", "first"), request("two", "second"))), Optional.of(continuation));
        });

        assertThat(service(store, model, tools).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.RETRY_SCHEDULED);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).appendAndScheduleRetry(eq(claim), batch.capture(), any(), eq(JobFailure.STORAGE_UNAVAILABLE), any());
        assertThat(batch.getValue().messages()).containsExactly(
                new ConversationStore.AssistantToolCallsData(Optional.of("Checking"), List.of(
                        new ConversationStore.ToolCallData(request("one", "first").toolCallId(), "first", Map.of()),
                        new ConversationStore.ToolCallData(request("two", "second").toolCallId(), "second", Map.of()))),
                new ConversationStore.ToolObservationData(request("one", "first").toolCallId(), "first", actual.asStructuredValue(),
                        Optional.of(artifactReceipt), Optional.of(skillReceipt)),
                new ConversationStore.ToolObservationData(request("two", "second").toolCallId(), "second",
                        ToolOutput.runtimeFailure("TOOL_NOT_EXECUTED", "Tool was not executed because Runtime storage failed.").asStructuredValue()));
        assertThat(batch.getValue().continuation()).contains(continuation);
        assertThat(secondInvocations).hasValue(0);
        verify(store, Mockito.never()).append(any(), any(), any());
        verify(store, Mockito.never()).scheduleRetry(any(), any(), any());
        ConversationStore.ToolObservationData completed = (ConversationStore.ToolObservationData) batch.getValue().messages().get(1);
        assertThat(completed.artifactReceipt().orElseThrow()).isSameAs(artifactReceipt);
        assertThat(completed.skillReadReceipt().orElseThrow()).isSameAs(skillReceipt);
        List<SessionMessage> retainedHistory = List.of(user(),
                new AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2), Optional.of(claim.messageJobId()),
                        Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking"),
                        List.of(request("one", "first"), request("two", "second"))),
                new ToolObservation(claim.sessionId(), new SessionSequence(3), Optional.of(claim.messageJobId()),
                        Instant.EPOCH, MessageRole.TOOL, completed.toolCallId(), completed.toolName(), completed.output()),
                new ToolObservation(claim.sessionId(), new SessionSequence(4), Optional.of(claim.messageJobId()),
                        Instant.EPOCH, MessageRole.TOOL, request("two", "second").toolCallId(), "second",
                        ((ConversationStore.ToolObservationData) batch.getValue().messages().get(2)).output()));
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(retainedHistory));
        when(store.loadContinuations(claim)).thenReturn(Map.of(new SessionSequence(2), continuation));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any(Instant.class)))
                .thenReturn(new ModelCallReservationResult.Reserved(2, 2));
        ConversationModel resumed = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            assertThat(request.history()).containsExactlyElementsOf(retainedHistory);
            assertThat(request.continuations()).containsEntry(new SessionSequence(2), continuation);
            return result(new ModelReply.Text("Recovered"));
        });
        assertThat(service(store, resumed, tools).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(secondInvocations).hasValue(0);
    }

    @Test
    void does_not_resubmit_a_pending_batch_when_atomic_retry_commit_is_uncertain() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty())));
        Mockito.doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (progress.phase() == JobPhase.TOOL_EXECUTION) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("admission failed"));
            }
            return null;
        }).when(store).updateProgress(eq(claim), any());
        when(store.appendAndScheduleRetry(eq(claim), any(), any(), any(), any()))
                .thenThrow(ConversationStoreFailure.transientFailure(new IllegalStateException("commit response lost")));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.UseTools(Optional.empty(), List.of(request("one", "first"))));
        });
        assertThat(service(store, model, catalog()).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.STATE_UNCONFIRMED);
        verify(store).appendAndScheduleRetry(eq(claim), any(), any(), any(), any());
        verify(store, Mockito.never()).append(any(), any(), any());
        verify(store, Mockito.never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void storage_recovery_uses_ordinary_allowance_not_total_control_request_count() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenThrow(
                ConversationStoreFailure.transientFailure(new IllegalStateException("storage interrupted")));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 20, Optional.empty(), Optional.empty(), Optional.empty())));
        when(store.loadJobControl(claim)).thenReturn(new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.EXECUTION,
                user(), Optional.empty(), 1, true, false));
        when(store.scheduleRetry(eq(claim), any(), eq(JobFailure.STORAGE_UNAVAILABLE))).thenReturn(true);
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> { throw new AssertionError("No model before history"); });

        assertThat(service(store, model, catalog(), 2).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.RETRY_SCHEDULED);
        verify(store, Mockito.never()).append(any(), any(), any());
    }


    @Test
    void does_not_invoke_a_tool_when_ownership_is_revoked_during_progress_publication() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any(Instant.class))).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        AtomicBoolean owned = new AtomicBoolean(true);
        Mockito.doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (progress.phase() == JobPhase.TOOL_EXECUTION) {
                owned.set(false);
            }
            return null;
        }).when(store).updateProgress(eq(claim), any(JobProgress.class));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.UseTools(Optional.empty(), List.of(request("call-1", "first"))));
        });
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            toolCalls.incrementAndGet();
            return new ToolOutput(false, Map.of("value", 1));
        })));

        MessageJobProcessingResult result = service(store, model, catalog).process(claim, owned::get);

        assertThat(result).isEqualTo(MessageJobProcessingResult.OWNERSHIP_LOST);
        assertThat(toolCalls).hasValue(0);
        verify(store, Mockito.never()).append(any(), any(), any());
    }

    @Test
    void does_not_call_the_provider_when_reservation_returns_after_ownership_is_revoked() throws Exception {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        CountDownLatch reservationStarted = new CountDownLatch(1);
        CountDownLatch releaseReservation = new CountDownLatch(1);
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any(Instant.class))).thenAnswer(invocation -> {
            reservationStarted.countDown();
            try {
                releaseReservation.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new ModelCallReservationResult.Reserved(1, 1);
        });
        AtomicInteger providerRequests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            providerRequests.incrementAndGet();
            return result(new ModelReply.Text("response"));
        });
        AtomicBoolean owned = new AtomicBoolean(true);
        ExecutorService processorExecutor = Executors.newSingleThreadExecutor();
        try {
            Future<MessageJobProcessingResult> processing = processorExecutor.submit(() ->
                    service(store, model, catalog()).process(claim, owned::get));
            assertThat(reservationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            owned.set(false);
            releaseReservation.countDown();

            assertThat(processing.get(5, TimeUnit.SECONDS)).isEqualTo(MessageJobProcessingResult.OWNERSHIP_LOST);
            assertThat(providerRequests).hasValue(0);
            verify(store, Mockito.never()).append(any(), any(), any(Instant.class));
        } finally {
            releaseReservation.countDown();
            processorExecutor.shutdownNow();
        }
    }

    @Test
    void completes_invalid_history_without_a_reservation_or_reclaimable_work() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> { throw ModelCallFailure.invalidHistory(); });

        service(store, model, catalog()).process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertTerminalFailure(batch.getValue(), "INVALID_CONVERSATION_HISTORY");
    }

    @Test
    void completes_invalid_persisted_native_history_without_reserving_or_calling_the_provider() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenThrow(ConversationStoreFailure.invalidHistory(
                new IllegalArgumentException("Malformed native call history")));
        ConversationModel model = mock(ConversationModel.class);

        service(store, model, catalog()).process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(store, Mockito.never()).reserveModelCall(any(), any(ModelCallPurpose.class), any(Integer.class), any(Instant.class));
        verify(model, Mockito.never()).respond(any(), any(), any());
        assertTerminalFailure(batch.getValue(), "INVALID_CONVERSATION_HISTORY");
    }

    @Test
    void does_not_report_a_storage_retry_when_the_claim_guarded_transition_is_rejected() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenThrow(ConversationStoreFailure.transientFailure(
                new IllegalStateException("storage unavailable")));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 0, Optional.empty(), Optional.empty(), Optional.empty())));
        when(store.scheduleRetry(eq(claim), any(Duration.class), eq(JobFailure.STORAGE_UNAVAILABLE))).thenReturn(false);
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("Provider must not be called after the storage failure");
        });
        Logger logger = (Logger) LoggerFactory.getLogger(JobProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MessageJobProcessingResult result;
        try {
            result = service(store, model, catalog()).process(claim, () -> true);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        verify(store).scheduleRetry(eq(claim), any(Duration.class), eq(JobFailure.STORAGE_UNAVAILABLE));
        assertThat(result).isEqualTo(MessageJobProcessingResult.OWNERSHIP_LOST);
        assertThat(appender.list).noneMatch(event -> "message_job_retry".equals(keyValue(event, "event")));
    }

    @Test
    void reports_retry_scheduled_only_after_the_claim_guarded_storage_transition_succeeds() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenThrow(ConversationStoreFailure.transientFailure(
                new IllegalStateException("storage unavailable")));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 0, Optional.empty(), Optional.empty(), Optional.empty())));
        when(store.scheduleRetry(eq(claim), any(Duration.class), eq(JobFailure.STORAGE_UNAVAILABLE))).thenReturn(true);
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("Provider must not be called after the storage failure");
        });

        MessageJobProcessingResult result = service(store, model, catalog()).process(claim, () -> true);

        assertThat(result).isEqualTo(MessageJobProcessingResult.RETRY_SCHEDULED);
        verify(store).scheduleRetry(eq(claim), any(Duration.class), eq(JobFailure.STORAGE_UNAVAILABLE));
    }

    @Test
    void reports_ownership_lost_when_the_claim_fence_is_stale_before_processing() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("Provider must not be called for a stale claim");
        });
        Mockito.doThrow(new StaleWorkClaimException()).when(store).bindModelRoute(claim, TEST_ROUTE_ID);

        MessageJobProcessingResult result = service(store, model, catalog()).process(claim, () -> true);

        assertThat(result).isEqualTo(MessageJobProcessingResult.OWNERSHIP_LOST);
        verify(store, Mockito.never()).append(any(), any(), any());
    }

    @Test
    void completes_model_unavailable_without_provider_or_tool_calls_when_the_job_route_differs() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        ConversationModel model = mock(ConversationModel.class);
        when(model.routeId()).thenReturn(new ModelRouteId("codex-primary"));
        Mockito.doThrow(new ModelRouteMismatchException()).when(store)
                .bindModelRoute(claim, new ModelRouteId("codex-primary"));

        service(store, model, catalog()).process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(store, Mockito.never()).loadContextHistory(any());
        verify(model, Mockito.never()).respond(any(), any(), any());
        assertTerminalFailure(batch.getValue(), "MODEL_UNAVAILABLE");
    }

    @Test
    void completes_model_unavailable_without_provider_or_tool_calls_when_the_persisted_fingerprint_is_no_longer_configured() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        ConversationModels models = new ConversationModels() {
            @Override
            public ModelSelection defaultSelection() {
                return new ModelSelection("test", "v2:current");
            }

            @Override
            public Optional<ModelSelection> findSelection(String model) {
                return Optional.empty();
            }

            @Override
            public Optional<ConversationModel> resolve(ModelSelection selection) {
                return Optional.empty();
            }
        };
        ToolCatalog catalog = () -> { throw new AssertionError("Tool snapshot must not be opened"); };
        JobProcessor processor = new JobProcessor(store, models, catalog, new FileSystemSkillLibrary(new SkillProperties()), java.time.Clock.systemUTC(), 2,
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, Duration.ofSeconds(60)),
                new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());

        processor.process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(store, Mockito.never()).loadContextHistory(any());
        assertTerminalFailure(batch.getValue(), "MODEL_UNAVAILABLE");
    }

    @Test
    void completes_model_unavailable_without_provider_or_tool_calls_when_a_loaded_continuation_route_differs() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        ConversationModel model = mock(ConversationModel.class);
        ModelRouteId routeId = new ModelRouteId("codex-primary");
        when(model.routeId()).thenReturn(routeId);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.loadContinuations(claim)).thenReturn(Map.of(new SessionSequence(2),
                new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1})));
        ToolCatalog catalog = () -> { throw new AssertionError("Tool snapshot must not be opened"); };

        service(store, model, catalog).process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(model, Mockito.never()).respond(any(), any(), any());
        verify(store, Mockito.never()).reserveModelCall(any(), any(ModelCallPurpose.class), any(Integer.class), any(Instant.class));
        assertTerminalFailure(batch.getValue(), "MODEL_UNAVAILABLE");
    }

    @Test
    void completes_model_unavailable_without_tools_or_persisting_a_returned_foreign_continuation() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        ConversationModel model = mock(ConversationModel.class);
        ModelRouteId routeId = new ModelRouteId("codex-primary");
        when(model.routeId()).thenReturn(routeId);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any(Instant.class))).thenReturn(new ModelCallReservationResult.Reserved(1, 1), new ModelCallReservationResult.BudgetExhausted());
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1});
        when(model.respond(any(), any(), any())).thenAnswer(invocation -> {
            ModelCallReservation reservation = invocation.getArgument(1);
            reservation.reserve();
            return new ModelCallResult(new ModelReply.UseTools(Optional.empty(), List.of(request("call-1", "first"))),
                    Optional.of(continuation));
        });
        AtomicBoolean toolInvoked = new AtomicBoolean();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            toolInvoked.set(true);
            return new ToolOutput(false, Map.of());
        })));

        service(store, model, catalog).process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(model).respond(any(), any(), any());
        assertThat(toolInvoked).isFalse();
        assertTerminalFailure(batch.getValue(), "MODEL_UNAVAILABLE");
        assertThat(batch.getValue().continuation()).isEmpty();
    }

    @Test
    void completes_terminal_continuation_replay_failure_once_without_reserving_or_executing_tools() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> { throw ModelCallFailure.terminal(); });
        AtomicBoolean toolInvoked = new AtomicBoolean();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            toolInvoked.set(true);
            return new ToolOutput(false, Map.of());
        })));

        service(store, model, catalog).process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(store, Mockito.never()).reserveModelCall(any(), any(ModelCallPurpose.class), any(Integer.class), any(Instant.class));
        assertThat(toolInvoked).isFalse();
        assertTerminalFailure(batch.getValue(), "MODEL_UNAVAILABLE");
    }

    private static Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value).findFirst().orElse(null); // cs-allow Optional test lookup uses null for a missing key
    }
}
