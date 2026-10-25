package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.IntentRequest;
import com.java.system.sessionagent.conversation.domain.IntentResult;

import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.JobControl;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.JobCancellation;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobPhase;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobCancellationTest {
    @Test
    void cancellation_after_wrap_response_prevents_a_waiting_prompt() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        JobControl control = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.WRAP_UP, user(), Optional.empty(), 1, false, false);
        when(store.loadJobControl(claim)).thenReturn(control);
        when(store.enterWrapUp(eq(claim), any())).thenReturn(control);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.WRAP_UP), eq(1), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(2, 1));
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            cancelled.set(true);
            return result(new ModelReply.Text("Do not publish this waiting question"));
        });

        assertThat(service(store, model, catalog(), 1).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);
        ConversationStore.MessageBatch batch = capturedBatch(store, claim);
        assertCancellation(batch);
        assertThat(batch.completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.NONE);
        assertThat(batch.messages()).noneMatch(ConversationStore.AssistantData.class::isInstance);
    }


    @Test
    void cancellation_winning_model_reservation_prevents_the_provider_request() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            throw new JobCancellationRequestedException();
        });
        AtomicInteger providerRequests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            providerRequests.incrementAndGet();
            return result(new ModelReply.Text("must not happen"));
        });

        assertThat(service(store, model, catalog()).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);

        assertThat(providerRequests).hasValue(0);
        assertCancellation(capturedBatch(store, claim));
    }

    @Test
    void finalizes_a_recovered_request_without_resolving_or_calling_a_model() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.isCancellationRequested(claim)).thenReturn(true);
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());
        ConversationModel model = mock(ConversationModel.class);

        assertThat(service(store, model, catalog()).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.CANCELLED);

        verify(model, never()).routeId();
        verify(model, never()).respond(any(), any(), any());
        verify(store, never()).reserveModelCall(any(), any(ModelCallPurpose.class), any(Integer.class), any());
        assertCancellation(capturedBatch(store, claim));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stops_after_an_inflight_model_success_or_failure_without_retrying(boolean fails) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());
        AtomicInteger requests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            requests.incrementAndGet();
            cancelled.set(true);
            if (fails) {
                throw ModelCallFailure.transientFailure();
            }
            return result(new ModelReply.Text("not committed"));
        });

        assertThat(service(store, model, catalog()).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.CANCELLED);

        assertThat(requests).hasValue(1);
        verify(store, never()).scheduleRetry(any(), any(), any());
        assertCancellation(capturedBatch(store, claim));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retains_the_actual_first_tool_result_and_marks_all_unstarted_calls_in_order(boolean firstFails) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        List<String> invoked = new ArrayList<>();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());
        ToolOutput actual = new ToolOutput(firstFails, Map.of("actual", "completed"));
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(
                binding("first", arguments -> { invoked.add("first"); cancelled.set(true); return actual; }),
                binding("second", arguments -> { invoked.add("second"); return new ToolOutput(false, Map.of()); }),
                binding("third", arguments -> { invoked.add("third"); return new ToolOutput(false, Map.of()); })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.UseTools(Optional.of("Checking"), List.of(
                    request("one", "first"), request("two", "second"), request("three", "third"))));
        });

        assertThat(service(store, model, catalog).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.CANCELLED);

        assertThat(invoked).containsExactly("first");
        ConversationStore.MessageBatch batch = capturedBatch(store, claim);
        assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.CANCEL);
        assertThat(batch.continuation()).isEmpty();
        assertThat(batch.failure()).isEmpty();
        assertThat(batch.messages()).containsExactly(
                new ConversationStore.AssistantToolCallsData(Optional.of("Checking"), List.of(
                        new ConversationStore.ToolCallData(request("one", "first").toolCallId(), "first", Map.of()),
                        new ConversationStore.ToolCallData(request("two", "second").toolCallId(), "second", Map.of()),
                        new ConversationStore.ToolCallData(request("three", "third").toolCallId(), "third", Map.of()))),
                new ConversationStore.ToolObservationData(request("one", "first").toolCallId(), "first", actual.asStructuredValue()),
                cancelledTool("two", "second"), cancelledTool("three", "third"));
        verify(store).reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retains_obtained_tool_observations_when_precommit_storage_recovers(boolean cancellationReadFails) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean failureInjected = new AtomicBoolean();
        AtomicInteger modelCalls = new AtomicInteger();
        List<String> invoked = new ArrayList<>();
        List<ConversationStore.MessageData> committed = new ArrayList<>();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> {
            if (cancellationReadFails && cancelled.get() && failureInjected.compareAndSet(false, true)) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("cancellation read failed once"));
            }
            return cancelled.get();
        });
        doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (!cancellationReadFails && progress.phase() == JobPhase.HISTORY_COMMIT
                    && failureInjected.compareAndSet(false, true)) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("history progress failed once"));
            }
            return null;
        }).when(store).updateProgress(eq(claim), any());
        doAnswer(invocation -> {
            ConversationStore.MessageBatch batch = invocation.getArgument(1);
            committed.addAll(batch.messages());
            if (batch.messages().stream().noneMatch(message -> message instanceof ConversationStore.RuntimeData runtime
                    && JobCancellation.CODE.equals(runtime.code()))) {
                committed.add(new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE));
            }
            return JobStatus.CANCELLED;
        }).when(store).append(eq(claim), any(), any());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        ToolOutput actual = new ToolOutput(false, Map.of("actual", "completed"));
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(
                binding("first", arguments -> { invoked.add("first"); cancelled.set(true); return actual; }),
                binding("second", arguments -> { invoked.add("second"); return new ToolOutput(false, Map.of()); })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            modelCalls.incrementAndGet();
            return result(new ModelReply.UseTools(Optional.of("Checking"), List.of(
                    request("one", "first"), request("two", "second"))));
        });

        assertThat(service(store, model, catalog).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);

        assertThat(failureInjected).isTrue();
        assertThat(modelCalls).hasValue(1);
        assertThat(invoked).containsExactly("first");
        assertThat(committed).containsExactly(
                new ConversationStore.AssistantToolCallsData(Optional.of("Checking"), List.of(
                        new ConversationStore.ToolCallData(request("one", "first").toolCallId(), "first", Map.of()),
                        new ConversationStore.ToolCallData(request("two", "second").toolCallId(), "second", Map.of()))),
                new ConversationStore.ToolObservationData(request("one", "first").toolCallId(), "first", actual.asStructuredValue()),
                cancellationReadFails
                        ? new ConversationStore.ToolObservationData(request("two", "second").toolCallId(), "second",
                                Map.of("isError", true, "result", Map.of("code", "TOOL_NOT_EXECUTED",
                                        "message", "Tool was not executed because Runtime storage failed.")))
                        : cancelledTool("two", "second"),
                new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE));
        ConversationStore.MessageBatch batch = capturedBatch(store, claim);
        assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.CANCEL);
        assertThat(batch.failure()).isEmpty();
        assertThat(batch.continuation()).isEmpty();
        verify(store, never()).scheduleRetry(any(), any(), any());
        verify(store).reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void terminal_storage_recovery_preserves_pending_tools_even_when_cancellation_wins_only_at_commit(
            boolean partialBatch, boolean cancellationWins) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean failureInjected = new AtomicBoolean();
        AtomicInteger modelCalls = new AtomicInteger();
        List<String> invoked = new ArrayList<>();
        List<ConversationStore.MessageData> committed = new ArrayList<>();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> {
            if (partialBatch && invoked.size() == 1 && failureInjected.compareAndSet(false, true)) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("pre-admission read failed"));
            }
            return false;
        });
        doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (!partialBatch && progress.phase() == JobPhase.HISTORY_COMMIT
                    && failureInjected.compareAndSet(false, true)) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("pre-append progress failed"));
            }
            return null;
        }).when(store).updateProgress(eq(claim), any());
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 3, 1,
                Optional.empty(), Optional.empty(), Optional.empty())));
        doAnswer(invocation -> {
            ConversationStore.MessageBatch batch = invocation.getArgument(1);
            // Model the winner by persisting only the actual submitted tool boundary, never synthesizing lost observations.
            batch.messages().stream().filter(message -> !(message instanceof ConversationStore.RuntimeData))
                    .forEach(committed::add);
            committed.add(cancellationWins
                    ? new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE)
                    : new ConversationStore.RuntimeData("STORAGE_UNAVAILABLE",
                            JobFailure.STORAGE_UNAVAILABLE.message()));
            return cancellationWins ? JobStatus.CANCELLED : JobStatus.DONE;
        }).when(store).append(eq(claim), any(), any());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        ToolOutput firstActual = new ToolOutput(false, Map.of("actual", "first"));
        ToolOutput secondActual = new ToolOutput(false, Map.of("actual", "second"));
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(
                binding("first", arguments -> { invoked.add("first"); return firstActual; }),
                binding("second", arguments -> { invoked.add("second"); return secondActual; })));
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "checkpoint-model", 12_000);
        ConversationModel model = new ConversationModel() {
            @Override public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation) {
                throw new AssertionError("Unexpected intent request in ordinary-job fixture");
            }
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usage) {
                reservation.reserve();
                modelCalls.incrementAndGet();
                return new ModelCallResult(new ModelReply.UseTools(Optional.of("Checking"), List.of(
                        request("one", "first"), request("two", "second"))), Optional.empty(), new ModelUsage(17, 3, 20, true));
            }
        };

        assertThat(service(store, model, catalog).process(claim, () -> true)).isEqualTo(
                cancellationWins ? MessageJobProcessingResult.CANCELLED : MessageJobProcessingResult.COMPLETED);

        assertThat(failureInjected).isTrue();
        assertThat(modelCalls).hasValue(1);
        assertThat(invoked).containsExactlyElementsOf(partialBatch ? List.of("first") : List.of("first", "second"));
        Object secondOutput = partialBatch
                ? Map.of("isError", true, "result", Map.of("code", "TOOL_NOT_EXECUTED",
                        "message", "Tool was not executed because Runtime storage failed."))
                : secondActual.asStructuredValue();
        assertThat(committed).containsExactly(
                new ConversationStore.AssistantToolCallsData(Optional.of("Checking"), List.of(
                        new ConversationStore.ToolCallData(request("one", "first").toolCallId(), "first", Map.of()),
                        new ConversationStore.ToolCallData(request("two", "second").toolCallId(), "second", Map.of()))),
                new ConversationStore.ToolObservationData(request("one", "first").toolCallId(), "first", firstActual.asStructuredValue()),
                new ConversationStore.ToolObservationData(request("two", "second").toolCallId(), "second", secondOutput),
                cancellationWins ? new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE)
                        : new ConversationStore.RuntimeData("STORAGE_UNAVAILABLE",
                                JobFailure.STORAGE_UNAVAILABLE.message()));
        ConversationStore.MessageBatch submitted = capturedBatch(store, claim);
        assertThat(submitted.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
        assertThat(submitted.usageCheckpoint()).isPresent();
        assertThat(submitted.usageCheckpoint().orElseThrow().promptTokens()).isEqualTo(17);
        assertThat(submitted.continuation()).isEmpty();
        verify(store, never()).scheduleRetry(any(), any(), any());
        verify(store).reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any());
    }

    @Test
    void leaves_an_uncertain_tool_batch_append_unconfirmed_without_resubmitting_or_finalizing_runtime_only() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger modelCalls = new AtomicInteger();
        List<String> invoked = new ArrayList<>();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        doAnswer(invocation -> {
            throw ConversationStoreFailure.transientFailure(new IllegalStateException("append outcome unknown"));
        }).when(store).append(eq(claim), any(), any());
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(
                binding("first", arguments -> {
                    invoked.add("first");
                    cancelled.set(true);
                    return new ToolOutput(false, Map.of("actual", "completed"));
                }),
                binding("second", arguments -> { invoked.add("second"); return new ToolOutput(false, Map.of()); })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            modelCalls.incrementAndGet();
            return result(new ModelReply.UseTools(Optional.empty(), List.of(
                    request("one", "first"), request("two", "second"))));
        });

        assertThat(service(store, model, catalog).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.STATE_UNCONFIRMED);

        assertThat(modelCalls).hasValue(1);
        assertThat(invoked).containsExactly("first");
        ConversationStore.MessageBatch attempted = capturedBatch(store, claim);
        assertThat(attempted.messages()).hasSize(3);
        assertThat(attempted.messages().getFirst()).isInstanceOf(ConversationStore.AssistantToolCallsData.class);
        assertThat(attempted.messages().getLast()).isEqualTo(cancelledTool("two", "second"));
        verify(store, never()).scheduleRetry(any(), any(), any());
        verify(store, never()).appendAndScheduleRetry(any(), any(), any(), any(), any());
    }

    @Test
    void reports_cancellation_winning_atomic_pending_batch_recovery_without_a_second_append() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty())));
        AtomicInteger admissions = new AtomicInteger();
        doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (progress.phase() == JobPhase.TOOL_EXECUTION && admissions.incrementAndGet() == 2) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("admission failed"));
            }
            return null;
        }).when(store).updateProgress(eq(claim), any());
        when(store.appendAndScheduleRetry(eq(claim), any(), any(), any(), any())).thenReturn(JobStatus.CANCELLED);
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.UseTools(Optional.empty(),
                    List.of(request("one", "first"), request("two", "second"))));
        });

        assertThat(service(store, model, catalog()).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);
        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).appendAndScheduleRetry(eq(claim), batch.capture(), any(), eq(JobFailure.STORAGE_UNAVAILABLE), any());
        assertThat(batch.getValue().messages()).hasSize(3);
        assertThat(batch.getValue().messages().get(1)).isEqualTo(new ConversationStore.ToolObservationData(
                request("one", "first").toolCallId(), "first", new ToolOutput(false, Map.of("value", 1)).asStructuredValue()));
        verify(store, never()).append(any(), any(), any());
        verify(store, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void cancellation_during_tool_admission_preserves_the_complete_unexecuted_batch() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());
        doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (progress.phase() == JobPhase.TOOL_EXECUTION) {
                cancelled.set(true);
                throw new JobCancellationRequestedException();
            }
            return null;
        }).when(store).updateProgress(eq(claim), any());
        AtomicInteger invocations = new AtomicInteger();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            invocations.incrementAndGet();
            return new ToolOutput(false, Map.of());
        })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.UseTools(Optional.empty(), List.of(request("one", "first"), request("two", "first"))));
        });

        assertThat(service(store, model, catalog).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);

        assertThat(invocations).hasValue(0);
        assertThat(capturedBatch(store, claim).messages().subList(1, 3)).containsExactly(
                cancelledTool("one", "first"), cancelledTool("two", "first"));
    }

    @Test
    void reports_the_cancelled_commit_winner_without_polling_again() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.Text("completed before commit race"));
        });

        assertThat(service(store, model, catalog()).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);

        verify(store, never()).readJob(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stops_after_inflight_compaction_without_persisting_or_retrying_it(boolean fails) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        List<SessionMessage> history = List.of(
                new AssistantMessage(claim.sessionId(), new SessionSequence(1), Optional.of(new MessageJobId("old")),
                        Instant.EPOCH, MessageRole.ASSISTANT, "x".repeat(40_000)),
                new UserMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.USER, "alice", "continue"));
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(history));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(2), any())).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());
        AtomicInteger summaries = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation) {
                throw new AssertionError("Unexpected intent request in ordinary-job fixture");
            }
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return new ModelDescriptor(TEST_ROUTE_ID, "capacity", 12_000); }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(ContextCompactionRequest request, ModelCallReservation reservation) {
                reservation.reserve();
                summaries.incrementAndGet();
                cancelled.set(true);
                if (fails) {
                    throw ModelCallFailure.transientFailure();
                }
                return "summary";
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usage) {
                throw new AssertionError("Ordinary model request must not start after cancellation");
            }
        };

        assertThat(service(store, model, catalog()).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);

        assertThat(summaries).hasValue(1);
        verify(store, never()).compact(any(), any(), any());
        verify(store, never()).scheduleRetry(any(), any(), any());
        assertCancellation(capturedBatch(store, claim));
    }

    @Test
    void cancellation_during_storage_recovery_is_finalized_instead_of_swallowed() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        when(store.loadContextHistory(claim.sessionId())).thenAnswer(invocation -> {
            cancelled.set(true);
            throw ConversationStoreFailure.transientFailure(new IllegalStateException("storage failed"));
        });
        doReturn(JobStatus.CANCELLED).when(store).append(eq(claim), any(), any());

        assertThat(service(store, model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("No model request expected");
        }), catalog()).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);

        verify(store, never()).scheduleRetry(any(), any(), any());
        assertCancellation(capturedBatch(store, claim));
    }

    @Test
    void cancellation_winning_storage_retry_admission_is_not_swallowed() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
        when(store.loadContextHistory(claim.sessionId())).thenThrow(
                ConversationStoreFailure.transientFailure(new IllegalStateException("storage failed")));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 0,
                Optional.empty(), Optional.empty(), Optional.empty())));
        when(store.scheduleRetry(eq(claim), any(), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            throw new JobCancellationRequestedException();
        });
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("Provider must not be called after storage failure");
        });

        assertThat(service(store, model, catalog()).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);

        assertCancellation(capturedBatch(store, claim));
    }

    private static ConversationStore.MessageBatch capturedBatch(ConversationStore store, MessageWorkClaim claim) {
        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any());
        return batch.getValue();
    }

    private static void assertCancellation(ConversationStore.MessageBatch batch) {
        assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.CANCEL);
        assertThat(batch.failure()).isEmpty();
        assertThat(batch.continuation()).isEmpty();
        assertThat(batch.messages()).containsExactly(new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE));
    }

    private static ConversationStore.ToolObservationData cancelledTool(String id, String name) {
        return new ConversationStore.ToolObservationData(request(id, name).toolCallId(), name,
                ToolOutput.runtimeFailure(JobCancellation.TOOL_CODE, JobCancellation.TOOL_MESSAGE).asStructuredValue());
    }
}
