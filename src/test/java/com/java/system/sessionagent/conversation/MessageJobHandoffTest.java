package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.domain.CacheTokenUsage;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import java.time.Clock;
import java.time.ZoneOffset;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import java.util.concurrent.atomic.AtomicReference;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.JobControl;
import com.java.system.sessionagent.conversation.domain.WaitingChoice;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.java.system.sessionagent.conversation.domain.IntentResult;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.application.JobHandoffCoordinator;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobHandoffTest {

    @Test
    void wrap_up_replays_visible_tool_continuation() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        ModelContinuation continuation = new ModelContinuation(TEST_ROUTE_ID, "opaque-v1", new byte[] {0, 1, -1, 42});
        List<SessionMessage> committedHistory = List.of(user(),
                new AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS,
                        Optional.of("Checking"), List.of(request("lookup", "first"))),
                new ToolObservation(claim.sessionId(), new SessionSequence(3), Optional.of(claim.messageJobId()),
                        Instant.EPOCH, MessageRole.TOOL, new ToolCallId("lookup"), "first",
                        new ToolOutput(false, Map.of("value", 1)).asStructuredValue()));
        AtomicReference<Map<SessionSequence, ModelContinuation>> retained = new AtomicReference<>(Map.of());
        when(store.loadContinuations(claim)).thenAnswer(invocation -> retained.get());
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())),
                contextHistory(committedHistory));
        org.mockito.Mockito.doAnswer(invocation -> {
            ConversationStore.MessageBatch batch = invocation.getArgument(1);
            if (batch.jobUpdate() == ConversationStore.JobUpdate.KEEP_WORKING) {
                retained.set(Map.of(new SessionSequence(2), batch.continuation().orElseThrow()));
            }
            return appendedStatus(batch);
        }).when(store).append(eq(claim), any(ConversationStore.MessageBatch.class), any(Instant.class));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.EXECUTION), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 1), new ModelCallReservationResult.Reserved(2, 2));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.WRAP_UP), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(3, 2));
        AtomicInteger providerRequests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            int totalOrdinal = reservation.reserve();
            int ordinal = providerRequests.incrementAndGet();
            usage.accept(new ModelCallObservation(totalOrdinal,
                    ordinal == 2 ? Optional.empty() : Optional.of(new CacheTokenUsage(8, 3))));
            if (ordinal == 1) {
                return new ModelCallResult(new ModelReply.UseTools(Optional.of("Checking"),
                        List.of(request("lookup", "first"))), Optional.of(continuation));
            }
            if (ordinal == 2) {
                return result(new ModelReply.UseTools(Optional.empty(), List.of(request("discarded", "second"))));
            }
            assertThat(request.purpose()).isEqualTo(ModelRequest.Purpose.WRAP_UP);
            assertThat(request.history()).containsExactlyElementsOf(committedHistory);
            assertThat(request.continuations()).containsExactly(Map.entry(new SessionSequence(2), continuation));
            assertThat(request.toolSnapshot().definitions()).isEmpty();
            return result(new ModelReply.Text("Saved evidence. Continue or organize?"));
        });

        assertThat(service(store, model, catalog(), 2).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.COMPLETED);

        assertThat(providerRequests).hasValue(3);
        ArgumentCaptor<ModelCallObservation> observations = ArgumentCaptor.forClass(ModelCallObservation.class);
        verify(store, times(3)).recordModelUsage(eq(claim), observations.capture());
        assertThat(observations.getAllValues()).containsExactly(
                new ModelCallObservation(1, Optional.of(new CacheTokenUsage(8, 3))),
                new ModelCallObservation(2, Optional.empty()),
                new ModelCallObservation(3, Optional.of(new CacheTokenUsage(8, 3))));
        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, times(2)).append(eq(claim), batches.capture(), any());
        assertThat(batches.getAllValues().getFirst().continuation()).contains(continuation);
        ConversationStore.MessageBatch waiting = batches.getAllValues().getLast();
        assertThat(waiting.completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.WAITING);
        assertThat(waiting.messages()).containsExactly(new ConversationStore.AssistantData("Saved evidence. Continue or organize?"));
        verify(store).reserveModelCall(eq(claim), eq(ModelCallPurpose.WRAP_UP), eq(2), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrap_replays_only_visible_assistant_batches_from_the_selected_compaction_snapshot(boolean wrongVisibleRoute) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        ModelContinuation hidden = new ModelContinuation(new ModelRouteId("other"), "opaque", new byte[] {1});
        ModelContinuation visible = new ModelContinuation(wrongVisibleRoute ? new ModelRouteId("other") : TEST_ROUTE_ID,
                "opaque", new byte[] {0, -1, 7});
        List<SessionMessage> history = List.of(user(), calls(claim, 2, "old"), observation(claim, 3, "old"),
                calls(claim, 4, "visible"), observation(claim, 5, "visible"));
        ContextSummary summary = new ContextSummary("Earlier evidence");
        ContextCompaction compaction = new ContextCompaction(1, claim.messageJobId(), ContextCompaction.Reason.THRESHOLD,
                summary, new SessionSequence(3), new ModelDescriptor(TEST_ROUTE_ID, "test", 32_000),
                "fingerprint", 100, 10, Instant.EPOCH);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(history));
        when(store.loadCompaction(claim.sessionId())).thenReturn(Optional.of(compaction));
        // A non-tool sequence must never turn into a replay entry either.
        when(store.loadContinuations(claim)).thenReturn(Map.of(new SessionSequence(2), hidden,
                new SessionSequence(4), visible, new SessionSequence(5), hidden));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.WRAP_UP), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(3, 2));
        AtomicInteger requests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            requests.incrementAndGet();
            assertThat(request.history()).containsExactly(history.get(3), history.get(4), user());
            assertThat(request.contextSummary()).contains(summary);
            assertThat(request.continuations()).containsExactly(Map.entry(new SessionSequence(4), visible));
            assertThat(request.continuations().get(new SessionSequence(4)).payload()).containsExactly(0, -1, 7);
            assertThat(request.toolSnapshot().definitions()).isEmpty();
            return result(new ModelReply.Text("Visible evidence saved"));
        });

        String response = new JobHandoffCoordinator(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2)
                .wrap(claim, () -> true, model);

        assertThat(response).isEqualTo(wrongVisibleRoute ? JobHandoffCoordinator.WRAP_FALLBACK : "Visible evidence saved");
        assertThat(requests).hasValue(wrongVisibleRoute ? 0 : 1);
        verify(store, times(1)).loadCompaction(claim.sessionId());
        if (wrongVisibleRoute) {
            verify(store, never()).reserveModelCall(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrap_without_continuation_preserves_pre_request_cancellation_and_ownership_checks(boolean cancelled) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.isCancellationRequested(claim)).thenReturn(cancelled);
        AtomicInteger requests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            assertThat(request.continuations()).isEmpty();
            reservation.reserve();
            requests.incrementAndGet();
            return result(new ModelReply.Text("must not send"));
        });
        JobHandoffCoordinator coordinator = new JobHandoffCoordinator(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2);

        assertThatThrownBy(() -> coordinator.wrap(claim, () -> cancelled, model))
                .isInstanceOf(cancelled ? JobCancellationRequestedException.class : StaleWorkClaimException.class);

        assertThat(requests).hasValue(0);
        verify(store, never()).reserveModelCall(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private static AssistantToolCallsMessage calls(MessageWorkClaim claim, long sequence, String id) {
        return new AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(sequence), Optional.of(claim.messageJobId()),
                Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking"), List.of(request(id, "first")));
    }

    private static ToolObservation observation(MessageWorkClaim claim, long sequence, String id) {
        return new ToolObservation(claim.sessionId(), new SessionSequence(sequence), Optional.of(claim.messageJobId()),
                Instant.EPOCH, MessageRole.TOOL, new ToolCallId(id), "first", Map.of("result", id));
    }

    @Test
    void a_plain_final_answer_on_the_last_allowed_request_completes_without_a_wrap_request() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(1), any(Instant.class))).thenReturn(new ModelCallReservationResult.Reserved(1, 1));
        AtomicInteger providerRequests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            providerRequests.incrementAndGet();
            return result(new ModelReply.Text("{\"intent\":\"DELIVER\",\"answer\":\"ordinary text\"}"));
        });

        MessageJobProcessingResult outcome = service(store, model, catalog(), 1).process(claim, () -> true);

        assertThat(outcome).isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(providerRequests).hasValue(1);
        ArgumentCaptor<ConversationStore.MessageBatch> committed = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), committed.capture(), any(Instant.class));
        assertThat(committed.getValue().messages()).containsExactly(new ConversationStore.AssistantData(
                "{\"intent\":\"DELIVER\",\"answer\":\"ordinary text\"}"));
    }

    @Test
    void last_allowed_tool_response_is_discarded_and_one_no_tools_wrap_question_is_committed() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(1), any(Instant.class)))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 1), new ModelCallReservationResult.Reserved(2, 1));
        AtomicInteger providerRequests = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            toolExecutions.incrementAndGet();
            return new ToolOutput(false, Map.of("value", 1));
        })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            if (providerRequests.incrementAndGet() == 1) {
                return result(new ModelReply.UseTools(Optional.of("Unexecuted investigation"),
                        List.of(request("discarded", "first"))));
            }
            assertThat(request.toolSnapshot().definitions()).isEmpty();
            assertThat(request.history()).containsExactly(user());
            return result(new ModelReply.Text("Existing records are saved. Continue investigating or organize the evidence?"));
        });

        MessageJobProcessingResult outcome = service(store, model, catalog, 1).process(claim, () -> true);

        assertThat(outcome).isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(toolExecutions).hasValue(0);
        assertThat(providerRequests).hasValue(2);
        ArgumentCaptor<ConversationStore.MessageBatch> committed = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), committed.capture(), any(Instant.class));
        assertThat(committed.getValue().jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
        assertThat(committed.getValue().completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.WAITING);
        assertThat(committed.getValue().messages()).containsExactly(new ConversationStore.AssistantData(
                "Existing records are saved. Continue investigating or organize the evidence?"));
    }

    @Test
    void a_failed_wrap_request_is_not_retried_or_replaced_with_an_investigation_request() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), any(ModelCallPurpose.class), eq(1), any(Instant.class)))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 1), new ModelCallReservationResult.Reserved(2, 1));
        AtomicInteger providerRequests = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            toolExecutions.incrementAndGet();
            return new ToolOutput(false, Map.of());
        })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            if (providerRequests.incrementAndGet() == 1) {
                return result(new ModelReply.UseTools(Optional.empty(), List.of(request("discarded", "first"))));
            }
            assertThat(request.toolSnapshot().definitions()).isEmpty();
            throw ModelCallFailure.correctable();
        });

        MessageJobProcessingResult outcome = service(store, model, catalog, 1).process(claim, () -> true);

        assertThat(outcome).isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(providerRequests).hasValue(2);
        assertThat(toolExecutions).hasValue(0);
        ArgumentCaptor<ConversationStore.MessageBatch> committed = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), committed.capture(), any(Instant.class));
        assertThat(committed.getValue().jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
        assertThat(committed.getValue().completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.WAITING);
        assertThat(committed.getValue().messages()).noneMatch(message ->
                message instanceof ConversationStore.AssistantToolCallsData || message instanceof ConversationStore.ToolObservationData);
        assertThat(committed.getValue().failure()).isEmpty();
    }
    @ParameterizedTest
    @EnumSource(value = FollowUpIntent.class, names = {"CONTINUE", "DELIVER", "NEW_QUESTION"})
    void semantic_decision_is_persisted_before_tools_and_only_deliver_restricts_capabilities(FollowUpIntent intent) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        UserMessage current = new UserMessage(claim.sessionId(), new SessionSequence(5), Optional.of(claim.messageJobId()),
                Instant.EPOCH, MessageRole.USER, "alice", "Please organize what you found, or investigate if that is what I chose.");
        UserMessage later = new UserMessage(claim.sessionId(), new SessionSequence(6), Optional.of(new MessageJobId("queued")),
                Instant.EPOCH, MessageRole.USER, "alice", "A later unrelated question");
        WaitingChoice wait = waiting();
        JobControl initial = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.INTENT, current, Optional.of(wait), 0, false, false);
        JobControl resolved = new JobControl(intent == FollowUpIntent.DELIVER ? JobControl.Mode.REPORT_ONLY : JobControl.Mode.NORMAL,
                JobControl.Stage.EXECUTION, current, Optional.empty(), 0, true, false);
        when(store.loadJobControl(claim)).thenReturn(initial, resolved);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(current, later)));
        AtomicBoolean persisted = new AtomicBoolean();
        when(store.resolveFollowUp(eq(claim), eq(wait), eq(intent), any())).thenAnswer(invocation -> {
            persisted.set(true);
            return resolved;
        });
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.INTENT), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 0));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.EXECUTION), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(2, 1), new ModelCallReservationResult.Reserved(3, 2));
        AtomicInteger ordinary = new AtomicInteger();
        AtomicInteger reports = new AtomicInteger();
        AtomicInteger responses = new AtomicInteger();
        ToolBinding source = new ToolBinding(ToolDefinition.fromExposedName("opaqueSource", "Lookup", Map.of()), (invocation, arguments) -> {
            assertThat(persisted).isTrue();
            ordinary.incrementAndGet();
            return new ToolOutput(false, Map.of());
        }, ToolCapability.ORDINARY);
        ToolBinding report = new ToolBinding(ToolDefinition.fromExposedName("opaqueReport", "Export", Map.of()), (invocation, arguments) -> {
            assertThat(persisted).isTrue();
            assertThat(invocation.context().modelCallNumber()).isEqualTo(2);
            reports.incrementAndGet();
            return new ToolOutput(false, Map.of());
        }, ToolCapability.REPORT_GENERATION);
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, observationObserver) -> {
            assertThat(request.initiatingMessage()).isEqualTo(current);
            assertThat(request.history()).containsExactly(current, later);
            assertThat(request.waiting()).isEqualTo(wait);
            observationObserver.accept(new ModelCallObservation(reservation.reserve(), Optional.empty()));
            return new IntentResult(intent, Optional.empty());
        }, (request, reservation, usage) -> {
            assertThat(persisted).isTrue();
            usage.accept(new ModelCallObservation(reservation.reserve(), Optional.of(new CacheTokenUsage(8, 3))));
            if (responses.incrementAndGet() == 1) {
                assertThat(request.toolSnapshot().definitions()).extracting(definition -> definition.name().value())
                        .contains("opaqueReport");
                if (intent == FollowUpIntent.DELIVER) {
                    assertThat(request.toolSnapshot().definitions()).extracting(definition -> definition.name().value())
                            .doesNotContain("opaqueSource");
                }
                return result(new ModelReply.UseTools(Optional.empty(), List.of(request("fabricated", "opaqueSource"), request("report", "opaqueReport"))));
            }
            return result(new ModelReply.Text("Evidence with unknown areas disclosed"));
        });

        assertThat(service(store, model, () -> new ToolSnapshot(List.of(source, report)), 2).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(ordinary).hasValue(intent == FollowUpIntent.DELIVER ? 0 : 1);
        assertThat(reports).hasValue(1);
        verify(store).recordModelUsage(claim, new ModelCallObservation(1, Optional.empty()));
        verify(store).recordModelUsage(claim, new ModelCallObservation(2, Optional.of(new CacheTokenUsage(8, 3))));
        verify(store).recordModelUsage(claim, new ModelCallObservation(3, Optional.of(new CacheTokenUsage(8, 3))));
        assertThat(responses).hasValue(2);
        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, times(2)).append(eq(claim), batches.capture(), any());
        assertThat(batches.getAllValues().getLast().completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.ANSWER);
        if (intent == FollowUpIntent.DELIVER) {
            assertThat(batches.getAllValues().getFirst().messages().get(1)).isInstanceOfSatisfying(ConversationStore.ToolObservationData.class,
                    observation -> assertThat(observation.output()).isEqualTo(ToolOutput.runtimeFailure(
                            "TOOL_NOT_AVAILABLE", "The requested tool is not available.").asStructuredValue()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguous_or_failed_intent_completes_waiting_without_loading_a_tool_catalog(boolean failed) {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        JobControl control = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.INTENT, user(), Optional.of(waiting()), 0, false, false);
        when(store.loadJobControl(claim)).thenReturn(control);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.INTENT), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 0));
        when(store.resolveFollowUp(eq(claim), eq(waiting()), eq(FollowUpIntent.CLARIFY), any())).thenReturn(control);
        AtomicInteger intentRequests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, observationObserver) -> {
            reservation.reserve();
            intentRequests.incrementAndGet();
            if (failed) {
                throw ModelCallFailure.correctable();
            }
            return new IntentResult(FollowUpIntent.CLARIFY, Optional.of("Which option would you like?"));
        }, (request, reservation, usage) -> { throw new AssertionError("Clarification must not request an answer"); });

        assertThat(service(store, model, () -> { throw new AssertionError("No tool catalog permitted"); }).process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(intentRequests).hasValue(1);
        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any());
        assertThat(batch.getValue().completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.WAITING);
        assertThat(batch.getValue().failure()).isEmpty();
        if (failed) {
            verify(store, never()).resolveFollowUp(any(), any(), any(), any());
        }
    }

    @Test
    void recovered_intent_reservation_is_fail_closed_without_reinterpreting_the_user() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        JobControl initial = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.INTENT, user(), Optional.of(waiting()), 0, false, false);
        JobControl attempted = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.INTENT, user(), Optional.of(waiting()), 0, true, false);
        when(store.loadJobControl(claim)).thenReturn(initial, attempted);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.INTENT), eq(2), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(1, 0));
        AtomicInteger intentRequests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, observationObserver) -> {
            reservation.reserve();
            intentRequests.incrementAndGet();
            throw new AssertionError("simulated process loss after reservation");
        }, (request, reservation, usage) -> { throw new AssertionError("No execution permission"); });
        ToolCatalog forbidden = () -> { throw new AssertionError("No tool catalog permitted"); };
        assertThatThrownBy(() -> service(store, model, forbidden).process(claim, () -> true)).isInstanceOf(AssertionError.class);
        assertThat(service(store, model, forbidden).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(intentRequests).hasValue(1);
        verify(store, never()).recordModelUsage(any(), any());
        verify(store, never()).resolveFollowUp(any(), any(), any(), any());
    }

    @Test
    void recovered_wrap_stage_never_resumes_execution_or_requeries_a_reserved_wrap() {
        ConversationStore store = mockStore();
        MessageWorkClaim claim = claim();
        JobControl initial = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.WRAP_UP, user(), Optional.empty(), 1, false, false);
        JobControl attempted = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.WRAP_UP, user(), Optional.empty(), 1, false, true);
        when(store.loadJobControl(claim)).thenReturn(initial);
        when(store.enterWrapUp(eq(claim), any())).thenReturn(initial, attempted);
        when(store.loadContextHistory(claim.sessionId())).thenReturn(contextHistory(List.of(user())));
        when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.WRAP_UP), eq(1), any()))
                .thenReturn(new ModelCallReservationResult.Reserved(2, 1));
        AtomicInteger requests = new AtomicInteger();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            assertThat(request.purpose()).isEqualTo(ModelRequest.Purpose.WRAP_UP);
            assertThat(request.toolSnapshot().definitions()).isEmpty();
            reservation.reserve();
            requests.incrementAndGet();
            throw new AssertionError("simulated process loss after wrap reservation");
        });
        ToolCatalog forbidden = () -> { throw new AssertionError("No ordinary execution snapshot permitted"); };
        assertThatThrownBy(() -> service(store, model, forbidden, 1).process(claim, () -> true)).isInstanceOf(AssertionError.class);
        assertThat(service(store, model, forbidden, 1).process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(requests).hasValue(1);
        verify(store, never()).recordModelUsage(any(), any());
        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any());
        assertThat(batch.getValue().completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.WAITING);
        assertThat(batch.getValue().messages()).containsExactly(new ConversationStore.AssistantData(JobHandoffCoordinator.WRAP_FALLBACK));
    }

    private static WaitingChoice waiting() {
        return new WaitingChoice(new MessageJobId("origin"), new SessionSequence(4), 1);
    }

}
