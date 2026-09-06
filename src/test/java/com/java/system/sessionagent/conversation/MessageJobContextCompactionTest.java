package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
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
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobContextCompactionTest {

    @Test
    void compacts_a_complete_tool_batch_before_the_ordinary_request_and_keeps_raw_history() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<SessionMessage> rawHistory = List.of(
                new UserMessage(claim.sessionId(), new SessionSequence(1), Optional.of(new MessageJobId("job-old")), Instant.EPOCH,
                        MessageRole.USER, "alice", "x".repeat(40_000)),
                new AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(new MessageJobId("job-old")), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.empty(),
                        List.of(request("call-1", "first"))),
                new ToolObservation(claim.sessionId(), new SessionSequence(3),
                        Optional.of(new MessageJobId("job-old")), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("value", "x".repeat(500)))),
                new UserMessage(claim.sessionId(), new SessionSequence(4), Optional.of(claim.messageJobId()), Instant.EPOCH,
                        MessageRole.USER, "alice", "continue"));
        AtomicReference<ContextCompaction> compacted = new AtomicReference<>();
        when(store.loadHistory(claim.sessionId())).thenReturn(rawHistory);
        when(store.loadCompaction(claim.sessionId())).thenAnswer(invocation -> Optional.ofNullable(compacted.get()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        Mockito.doAnswer(invocation -> {
            ConversationStore.CompactionData data = invocation.getArgument(1);
            compacted.set(new ContextCompaction(data.generation(), claim.messageJobId(), data.reason(), new ContextSummary(data.summary()),
                    data.coveredThrough(), data.model(), data.requestShapeFingerprint(), data.estimateBeforeTokens(),
                    data.estimateAfterTokens(), Instant.EPOCH));
            return null;
        }).when(store).compact(eq(claim), any(ConversationStore.CompactionData.class), any(Instant.class));
        List<String> callOrder = new ArrayList<>();
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "test-capacity", 12_000);
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(ContextCompactionRequest request,
                    ModelCallReservation reservation) {
                callOrder.add("compact");
                reservation.reserve();
                return "old discussion summary";
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usageObserver) {
                callOrder.add("ordinary");
                assertThat(request.contextSummary()).contains(new ContextSummary("old discussion summary"));
                assertThat(request.history()).containsExactly(rawHistory.get(3));
                reservation.reserve();
                return result(new ModelReply.Text("done"));
            }
        };

        service(store, model, catalog()).process(claim, () -> true);

        assertThat(callOrder).containsExactly("compact", "ordinary");
        assertThat(rawHistory).extracting(message -> message.sequence().value()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(compacted.get()).isNotNull();
        assertThat(compacted.get().coveredThrough().value()).isEqualTo(3L);
    }

    @Test
    void completes_context_too_large_without_persisting_a_threshold_compaction_when_its_summary_remains_oversized() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<SessionMessage> history = List.of(
                new UserMessage(claim.sessionId(), new SessionSequence(1), Optional.of(new MessageJobId("job-old")), Instant.EPOCH,
                        MessageRole.USER, "alice", "x".repeat(40_000)),
                new AssistantMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(new MessageJobId("job-old")), Instant.EPOCH, MessageRole.ASSISTANT, "previous answer"),
                new UserMessage(claim.sessionId(), new SessionSequence(3), Optional.of(claim.messageJobId()), Instant.EPOCH,
                        MessageRole.USER, "alice", "continue"));
        AtomicReference<ContextCompaction> compacted = new AtomicReference<>();
        when(store.loadHistory(claim.sessionId())).thenReturn(history);
        when(store.loadCompaction(claim.sessionId())).thenAnswer(invocation -> Optional.ofNullable(compacted.get()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1));
        Mockito.doAnswer(invocation -> {
            ConversationStore.CompactionData data = invocation.getArgument(1);
            compacted.set(new ContextCompaction(data.generation(), claim.messageJobId(), data.reason(), new ContextSummary(data.summary()),
                    data.coveredThrough(), data.model(), data.requestShapeFingerprint(), data.estimateBeforeTokens(),
                    data.estimateAfterTokens(), Instant.EPOCH));
            return null;
        }).when(store).compact(eq(claim), any(ConversationStore.CompactionData.class), any(Instant.class));
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "test-capacity", 12_000);
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(ContextCompactionRequest request,
                    ModelCallReservation reservation) {
                reservation.reserve();
                return "summary ".repeat(5_000);
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usage) {
                throw new AssertionError("An oversized compacted context must not make an ordinary model request");
            }
        };

        MessageJobProcessingResult result = service(store, model, catalog()).process(claim, () -> true);

        assertThat(result).isEqualTo(MessageJobProcessingResult.COMPLETED);
        verify(store, Mockito.never()).compact(any(), any(), any());
        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("CONTEXT_TOO_LARGE", "Runtime model context is too large.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void compacts_the_current_jobs_complete_tool_batch_before_continuing() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        ModelContinuation continuation = new ModelContinuation(TEST_ROUTE_ID, "opaque-v1", new byte[] {1, 2, 3});
        UserMessage currentUser = user();
        List<SessionMessage> committedHistory = List.of(
                currentUser,
                new AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking."),
                        List.of(request("call-1", "first"), request("call-2", "second"))),
                new ToolObservation(claim.sessionId(), new SessionSequence(3),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("value", "x".repeat(20_000)))),
                new ToolObservation(claim.sessionId(), new SessionSequence(4),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-2"), "second",
                        Map.of("isError", false, "result", Map.of("value", "y".repeat(20_000)))));
        AtomicReference<ContextCompaction> compacted = new AtomicReference<>();
        AtomicReference<Map<SessionSequence, ModelContinuation>> continuations = new AtomicReference<>(Map.of());
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(currentUser)).thenReturn(committedHistory);
        when(store.loadContinuations(claim)).thenAnswer(invocation -> continuations.get());
        when(store.loadCompaction(claim.sessionId())).thenAnswer(invocation -> Optional.ofNullable(compacted.get()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2), OptionalInt.of(3));
        Mockito.doAnswer(invocation -> {
            ConversationStore.MessageBatch batch = invocation.getArgument(1);
            continuations.set(batch.continuation().map(value -> Map.of(new SessionSequence(2), value)).orElseGet(Map::of));
            return null;
        }).when(store).append(eq(claim), any(ConversationStore.MessageBatch.class), any(Instant.class));
        Mockito.doAnswer(invocation -> {
            ConversationStore.CompactionData data = invocation.getArgument(1);
            compacted.set(new ContextCompaction(data.generation(), claim.messageJobId(), data.reason(), new ContextSummary(data.summary()),
                    data.coveredThrough(), data.model(), data.requestShapeFingerprint(), data.estimateBeforeTokens(),
                    data.estimateAfterTokens(), Instant.EPOCH));
            continuations.set(Map.of());
            return null;
        }).when(store).compact(eq(claim), any(ConversationStore.CompactionData.class), any(Instant.class));
        List<String> callOrder = new ArrayList<>();
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "test-capacity", 12_000);
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(ContextCompactionRequest request,
                    ModelCallReservation reservation) {
                callOrder.add("compact");
                assertThat(request.history()).containsExactlyElementsOf(committedHistory);
                reservation.reserve();
                return "current tool summary";
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usage) {
                callOrder.add("ordinary");
                if (callOrder.size() == 1) {
                    assertThat(request.history()).containsExactly(currentUser);
                    reservation.reserve();
                    return new ModelCallResult(new ModelReply.UseTools(Optional.of("Checking."), List.of(
                            request("call-1", "first"), request("call-2", "second"))), Optional.of(continuation));
                }
                assertThat(request.contextSummary()).contains(new ContextSummary("current tool summary"));
                assertThat(request.history()).isEmpty();
                assertThat(request.continuations()).isEmpty();
                reservation.reserve();
                return result(new ModelReply.Text("done"));
            }
        };
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(
                binding("first", arguments -> new ToolOutput(false, Map.of("value", "x".repeat(20_000)))),
                binding("second", arguments -> new ToolOutput(false, Map.of("value", "y".repeat(20_000))))));

        MessageJobProcessingResult result = service(store, model, catalog, 3).process(claim, () -> true);

        assertThat(result).isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(callOrder).containsExactly("ordinary", "compact", "ordinary");
        assertThat(committedHistory).extracting(message -> message.sequence().value()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(compacted.get()).isNotNull();
        assertThat(compacted.get().coveredThrough().value()).isEqualTo(4L);
        verify(store, Mockito.times(3)).reserveModelCall(eq(claim), eq(3), any(Instant.class));
    }

    @Test
    void does_not_compact_an_incomplete_current_job_tool_batch() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<SessionMessage> incompleteHistory = List.of(
                user(),
                new AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking."),
                        List.of(request("call-1", "first"), request("call-2", "second"))),
                new ToolObservation(claim.sessionId(), new SessionSequence(3),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("value", "x".repeat(40_000)))));
        when(store.loadHistory(claim.sessionId())).thenReturn(incompleteHistory);
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "test-capacity", 12_000);
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(ContextCompactionRequest request,
                    ModelCallReservation reservation) {
                throw new AssertionError("Incomplete tool batches must not be summarized");
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usage) {
                throw new AssertionError("Context threshold requires compaction before an ordinary request");
            }
        };

        service(store, model, catalog()).process(claim, () -> true);

        verify(store, Mockito.never()).compact(any(), any(), any());
        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("CONTEXT_TOO_LARGE", "Runtime model context is too large.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void recovers_one_context_overflow_with_a_persisted_compaction_then_retries_once() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<SessionMessage> history = List.of(
                new UserMessage(claim.sessionId(), new SessionSequence(1), Optional.of(new MessageJobId("job-old")), Instant.EPOCH,
                        MessageRole.USER, "alice", "x".repeat(40_000)),
                new AssistantMessage(claim.sessionId(), new SessionSequence(2), Optional.of(new MessageJobId("job-old")), Instant.EPOCH,
                        MessageRole.ASSISTANT, "previous answer"),
                new UserMessage(claim.sessionId(), new SessionSequence(3), Optional.of(claim.messageJobId()), Instant.EPOCH,
                        MessageRole.USER, "alice", "continue"));
        AtomicReference<ContextCompaction> compacted = new AtomicReference<>();
        when(store.loadHistory(claim.sessionId())).thenReturn(history);
        when(store.loadCompaction(claim.sessionId())).thenAnswer(invocation -> Optional.ofNullable(compacted.get()));
        when(store.hasOverflowCompaction(claim.messageJobId())).thenReturn(false);
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2), OptionalInt.of(3));
        Mockito.doAnswer(invocation -> {
            ConversationStore.CompactionData data = invocation.getArgument(1);
            compacted.set(new ContextCompaction(data.generation(), claim.messageJobId(), data.reason(), new ContextSummary(data.summary()),
                    data.coveredThrough(), data.model(), data.requestShapeFingerprint(), data.estimateBeforeTokens(),
                    data.estimateAfterTokens(), Instant.EPOCH));
            return null;
        }).when(store).compact(eq(claim), any(ConversationStore.CompactionData.class), any(Instant.class));
        List<String> calls = new ArrayList<>();
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "test-capacity", 20_000);
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(ContextCompactionRequest request,
                    ModelCallReservation reservation) {
                calls.add("compact");
                reservation.reserve();
                return "previous summary";
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usageObserver) {
                calls.add("ordinary");
                reservation.reserve();
                if (calls.size() == 1) {
                    throw ModelCallFailure.contextTooLarge();
                }
                return result(new ModelReply.Text("done"));
            }
        };

        service(store, model, catalog(), 3).process(claim, () -> true);

        assertThat(calls).containsExactly("ordinary", "compact", "ordinary");
        assertThat(compacted.get().reason()).isEqualTo(ContextCompaction.Reason.OVERFLOW);
    }
}
