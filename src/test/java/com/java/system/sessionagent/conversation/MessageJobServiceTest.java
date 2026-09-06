package com.java.system.sessionagent.conversation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.ContextSummary;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobServiceTest {

    private static final ModelRouteId TEST_ROUTE_ID = new ModelRouteId("test");

    @Test
    void completes_an_unrestricted_text_reply_without_tool_execution() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.Text("```json\n{\"answer\":\"plain completion\"}\n```"));
        });

        MessageJobProcessingResult result = service(store, model, catalog()).process(claim, () -> true);

        assertThat(result).isEqualTo(MessageJobProcessingResult.COMPLETED);
        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantData("```json\n{\"answer\":\"plain completion\"}\n```")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void compacts_a_complete_tool_batch_before_the_ordinary_request_and_keeps_raw_history() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> rawHistory = List.of(
                new UserMessage(claim.sessionId(), new SessionSequence(1), Optional.of(new MessageJobId("job-old")), Instant.EPOCH,
                        MessageRole.USER, "alice", "x".repeat(40_000)),
                new com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(new MessageJobId("job-old")), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.empty(),
                        List.of(request("call-1", "first"))),
                new com.java.system.sessionagent.conversation.domain.ToolObservation(claim.sessionId(), new SessionSequence(3),
                        Optional.of(new MessageJobId("job-old")), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("value", "x".repeat(500)))),
                new UserMessage(claim.sessionId(), new SessionSequence(4), Optional.of(claim.messageJobId()), Instant.EPOCH,
                        MessageRole.USER, "alice", "continue"));
        AtomicReference<ContextCompaction> compacted = new AtomicReference<>();
        when(store.loadHistory(claim.sessionId())).thenReturn(rawHistory);
        when(store.loadCompaction(claim.sessionId())).thenAnswer(invocation -> Optional.ofNullable(compacted.get()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        org.mockito.Mockito.doAnswer(invocation -> {
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
            @Override public String summarize(com.java.system.sessionagent.conversation.domain.ContextCompactionRequest request,
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
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> history = List.of(
                new UserMessage(claim.sessionId(), new SessionSequence(1), Optional.of(new MessageJobId("job-old")), Instant.EPOCH,
                        MessageRole.USER, "alice", "x".repeat(40_000)),
                new com.java.system.sessionagent.conversation.domain.AssistantMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(new MessageJobId("job-old")), Instant.EPOCH, MessageRole.ASSISTANT, "previous answer"),
                new UserMessage(claim.sessionId(), new SessionSequence(3), Optional.of(claim.messageJobId()), Instant.EPOCH,
                        MessageRole.USER, "alice", "continue"));
        AtomicReference<ContextCompaction> compacted = new AtomicReference<>();
        when(store.loadHistory(claim.sessionId())).thenReturn(history);
        when(store.loadCompaction(claim.sessionId())).thenAnswer(invocation -> Optional.ofNullable(compacted.get()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1));
        org.mockito.Mockito.doAnswer(invocation -> {
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
            @Override public String summarize(com.java.system.sessionagent.conversation.domain.ContextCompactionRequest request,
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
        verify(store, org.mockito.Mockito.never()).compact(any(), any(), any());
        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
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
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> committedHistory = List.of(
                currentUser,
                new com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking."),
                        List.of(request("call-1", "first"), request("call-2", "second"))),
                new com.java.system.sessionagent.conversation.domain.ToolObservation(claim.sessionId(), new SessionSequence(3),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("value", "x".repeat(20_000)))),
                new com.java.system.sessionagent.conversation.domain.ToolObservation(claim.sessionId(), new SessionSequence(4),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-2"), "second",
                        Map.of("isError", false, "result", Map.of("value", "y".repeat(20_000)))));
        AtomicReference<ContextCompaction> compacted = new AtomicReference<>();
        AtomicReference<Map<SessionSequence, ModelContinuation>> continuations = new AtomicReference<>(Map.of());
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(currentUser), committedHistory);
        when(store.loadContinuations(claim)).thenAnswer(invocation -> continuations.get());
        when(store.loadCompaction(claim.sessionId())).thenAnswer(invocation -> Optional.ofNullable(compacted.get()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2), OptionalInt.of(3));
        org.mockito.Mockito.doAnswer(invocation -> {
            ConversationStore.MessageBatch batch = invocation.getArgument(1);
            continuations.set(batch.continuation().map(value -> Map.of(new SessionSequence(2), value)).orElseGet(Map::of));
            return null;
        }).when(store).append(eq(claim), any(ConversationStore.MessageBatch.class), any(Instant.class));
        org.mockito.Mockito.doAnswer(invocation -> {
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
            @Override public String summarize(com.java.system.sessionagent.conversation.domain.ContextCompactionRequest request,
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
        verify(store, org.mockito.Mockito.times(3)).reserveModelCall(eq(claim), eq(3), any(Instant.class));
    }

    @Test
    void does_not_compact_an_incomplete_current_job_tool_batch() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> incompleteHistory = List.of(
                user(),
                new com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage(claim.sessionId(), new SessionSequence(2),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking."),
                        List.of(request("call-1", "first"), request("call-2", "second"))),
                new com.java.system.sessionagent.conversation.domain.ToolObservation(claim.sessionId(), new SessionSequence(3),
                        Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("value", "x".repeat(40_000)))));
        when(store.loadHistory(claim.sessionId())).thenReturn(incompleteHistory);
        ModelDescriptor descriptor = new ModelDescriptor(TEST_ROUTE_ID, "test-capacity", 12_000);
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return TEST_ROUTE_ID; }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(com.java.system.sessionagent.conversation.domain.ContextCompactionRequest request,
                    ModelCallReservation reservation) {
                throw new AssertionError("Incomplete tool batches must not be summarized");
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usage) {
                throw new AssertionError("Context threshold requires compaction before an ordinary request");
            }
        };

        service(store, model, catalog()).process(claim, () -> true);

        verify(store, org.mockito.Mockito.never()).compact(any(), any(), any());
        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("CONTEXT_TOO_LARGE", "Runtime model context is too large.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void recovers_one_context_overflow_with_a_persisted_compaction_then_retries_once() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> history = List.of(
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
        org.mockito.Mockito.doAnswer(invocation -> {
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
            @Override public String summarize(com.java.system.sessionagent.conversation.domain.ContextCompactionRequest request,
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

    @Test
    void releases_the_snapshot_after_a_final_text_reply() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        AtomicInteger releases = new AtomicInteger();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.Text("done"));
        });
        ToolCatalog catalog = () -> trackedSnapshot(releases);

        service(store, model, catalog).process(claim, () -> true);

        assertThat(releases.get()).isEqualTo(1);
    }

    @Test
    void keeps_the_snapshot_open_for_its_tool_batch_then_releases_it() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<String> releases = new ArrayList<>();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        ToolCatalog catalog = () -> {
            int snapshotIndex = snapshots.getAndIncrement();
            return new ToolSnapshot(List.of(binding("first", arguments -> {
                assertThat(releases).doesNotContain("snapshot-" + snapshotIndex);
                return new ToolOutput(false, Map.of());
            })), () -> releases.add("snapshot-" + snapshotIndex));
        };
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            int modelCall = modelCalls.getAndIncrement();
            if (modelCall == 0) { // cs-allow first model call has the tool batch
                return result(new ModelReply.UseTools(Optional.empty(), List.of(request("call-1", "first"))));
            }
            assertThat(releases).containsExactly("snapshot-0");
            return result(new ModelReply.Text("done"));
        });

        service(store, model, catalog).process(claim, () -> true);

        assertThat(modelCalls).hasValue(2);
        assertThat(releases).containsExactly("snapshot-0", "snapshot-1");
    }

    @Test
    void releases_the_snapshot_when_the_model_fails() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        AtomicInteger releases = new AtomicInteger();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> { throw ModelCallFailure.invalidHistory(); });

        service(store, model, () -> trackedSnapshot(releases)).process(claim, () -> true);

        assertThat(releases.get()).isEqualTo(1);
    }

    @Test
    void releases_the_snapshot_when_work_ownership_is_lost_after_the_model_reply() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        AtomicInteger releases = new AtomicInteger();
        AtomicBoolean owned = new AtomicBoolean(true);
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            owned.set(false);
            return result(new ModelReply.Text("ignored after ownership loss"));
        });

        service(store, model, () -> trackedSnapshot(releases)).process(claim, owned::get);

        assertThat(releases.get()).isEqualTo(1);
    }

    @Test
    void releases_the_snapshot_when_the_model_call_limit_blocks_a_tool_batch() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        AtomicInteger releases = new AtomicInteger();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(1), any(Instant.class))).thenReturn(OptionalInt.of(1));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.UseTools(Optional.empty(), List.of(request("call-1", "first"))));
        });

        service(store, model, () -> trackedSnapshot(releases), 1).process(claim, () -> true);

        assertThat(releases.get()).isEqualTo(1);
    }

    @Test
    void commits_native_assistant_calls_and_ordered_outputs_as_one_batch() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(request.history().size() == 1
                    ? new ModelReply.UseTools(Optional.of("Checking."), List.of(
                    new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of("query", "fees")),
                    new ToolRequest(new ToolCallId("call-2"), new ToolName("second"), Map.of())))
                    : new ModelReply.Text("Done"));
        });

        service(store, model, catalog()).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batches = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        assertThat(batches.getAllValues().getFirst().messages()).containsExactly(
                new ConversationStore.AssistantToolCallsData(Optional.of("Checking."), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "first", Map.of("query", "fees")),
                        new ConversationStore.ToolCallData(new ToolCallId("call-2"), "second", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of("value", 1))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-2"), "second", Map.of("isError", true, "result", Map.of("code", "TOOL_TIMEOUT"))));
    }

    @Test
    void reloads_the_committed_continuation_for_the_next_model_call() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1, 2, 3});
        List<com.java.system.sessionagent.conversation.domain.ModelRequest> requests = new ArrayList<>();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user())).thenReturn(List.of(user()));
        when(store.loadContinuations(claim)).thenReturn(Map.of())
                .thenReturn(Map.of(new SessionSequence(2), continuation));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        ConversationModel model = model(new ModelRouteId("gemini-primary"), (request, reservation, usage) -> {
            requests.add(request);
            reservation.reserve();
            return requests.size() == 1
                    ? new ModelCallResult(new ModelReply.UseTools(Optional.empty(), List.of(request("call-1", "first"))),
                    Optional.of(continuation))
                    : result(new ModelReply.Text("done"));
        });

        service(store, model, catalog()).process(claim, () -> true);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).continuations()).containsExactly(Map.entry(new SessionSequence(2), continuation));
        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batches = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        assertThat(batches.getAllValues().getFirst().continuation()).contains(continuation);
    }

    @Test
    void preserves_nested_tool_arguments_when_an_executor_mutates_them() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("filters", new LinkedHashMap<>(Map.of("branch", "main")));
        arguments.put("paths", new ArrayList<>(List.of("src")));
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", supplied -> {
            Map<?, ?> filters = (Map<?, ?>) supplied.get("filters");
            List<?> paths = (List<?>) supplied.get("paths");
            assertThatThrownBy(filters::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(paths::clear).isInstanceOf(UnsupportedOperationException.class);
            return new ToolOutput(false, Map.of());
        })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(request.history().size() == 1
                    ? new ModelReply.UseTools(Optional.empty(), List.of(new ToolRequest(
                    new ToolCallId("call-1"), new ToolName("first"), arguments)))
                    : new ModelReply.Text("Done"));
        });

        service(store, model, catalog).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batches = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        ConversationStore.AssistantToolCallsData calls = (ConversationStore.AssistantToolCallsData) batches.getAllValues().getFirst().messages().getFirst();
        assertThat(calls.calls().getFirst().arguments()).isEqualTo(Map.of(
                "filters", Map.of("branch", "main"),
                "paths", List.of("src")));
    }

    @Test
    void executes_later_calls_after_an_ordinary_middle_failure() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        List<String> executed = new ArrayList<>();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(
                binding("first", arguments -> { executed.add("first"); return new ToolOutput(false, Map.of("value", 1)); }),
                binding("second", arguments -> { executed.add("second"); throw new IllegalStateException("ordinary failure"); }),
                binding("third", arguments -> { executed.add("third"); return new ToolOutput(false, Map.of("value", 3)); })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(request.history().size() == 1
                    ? new ModelReply.UseTools(Optional.empty(), List.of(
                    request("call-1", "first"), request("call-2", "second"), request("call-3", "third")))
                    : new ModelReply.Text("done"));
        });

        service(store, model, catalog).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batches = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        assertThat(executed).containsExactly("first", "second", "third");
        assertThat(batches.getAllValues().getFirst().messages()).containsExactly(
                new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "first", Map.of()),
                        new ConversationStore.ToolCallData(new ToolCallId("call-2"), "second", Map.of()),
                        new ConversationStore.ToolCallData(new ToolCallId("call-3"), "third", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of("value", 1))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-2"), "second", Map.of("isError", true, "result", Map.of(
                        "code", "TOOL_PROTOCOL_ERROR", "message", "The tool could not be executed."))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-3"), "third", Map.of("isError", false, "result", Map.of("value", 3))));
    }

    @Test
    void keeps_a_response_on_the_final_ordinal_from_executing_tools() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(1), any(Instant.class))).thenReturn(OptionalInt.of(1));
        AtomicBoolean invoked = new AtomicBoolean();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            invoked.set(true);
            return new ToolOutput(false, Map.of());
        })));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            return result(new ModelReply.UseTools(Optional.of("intermediate"), List.of(request("call-1", "first"))));
        });

        service(store, model, catalog, 1).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(invoked).isFalse();
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("MODEL_CALL_LIMIT_REACHED", "Runtime model call limit reached.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void uses_the_captured_snapshot_for_a_batch_and_refreshes_before_the_next_native_replay() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> replayedHistory = nativeHistory();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user())).thenReturn(replayedHistory);
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        ToolSnapshot firstSnapshot = new ToolSnapshot(List.of(binding("first", arguments -> new ToolOutput(false, Map.of("snapshot", "first")))));
        ToolSnapshot refreshedSnapshot = new ToolSnapshot(List.of(binding("second", arguments -> new ToolOutput(false, Map.of("snapshot", "second")))));
        AtomicBoolean firstRead = new AtomicBoolean();
        ToolCatalog catalog = () -> firstRead.compareAndSet(false, true) ? firstSnapshot : refreshedSnapshot;
        List<com.java.system.sessionagent.conversation.domain.ModelRequest> requests = new ArrayList<>();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            requests.add(request);
            reservation.reserve();
            return result(requests.size() == 1
                    ? new ModelReply.UseTools(Optional.of("Checking."), List.of(request("call-1", "first")))
                    : new ModelReply.Text("done"));
        });

        service(store, model, catalog).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batches = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        assertThat(batches.getAllValues().getFirst().messages().get(1)).isEqualTo(
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "first", Map.of("isError", false,
                        "result", Map.of("snapshot", "first"))));
        assertThat(requests.get(1).history()).isEqualTo(replayedHistory);
        assertThat(requests.get(1).toolSnapshot()).isEqualTo(refreshedSnapshot);
    }

    @Test
    void completes_invalid_history_without_a_reservation_or_reclaimable_work() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> { throw ModelCallFailure.invalidHistory(); });

        service(store, model, catalog()).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("INVALID_CONVERSATION_HISTORY", "Runtime conversation history is invalid.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void completes_invalid_persisted_native_history_without_reserving_or_calling_the_provider() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenThrow(ConversationStoreFailure.invalidHistory(
                new IllegalArgumentException("Malformed native call history")));
        ConversationModel model = mock(ConversationModel.class);

        service(store, model, catalog()).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(store, org.mockito.Mockito.never()).reserveModelCall(any(), any(Integer.class), any(Instant.class));
        verify(model, org.mockito.Mockito.never()).respond(any(), any(), any());
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("INVALID_CONVERSATION_HISTORY", "Runtime conversation history is invalid.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void does_not_report_a_storage_retry_when_the_claim_guarded_transition_is_rejected() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenThrow(ConversationStoreFailure.transientFailure(
                new IllegalStateException("storage unavailable")));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 0)));
        when(store.scheduleRetry(eq(claim), any(Duration.class))).thenReturn(false);
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("Provider must not be called after the storage failure");
        });
        Logger logger = (Logger) LoggerFactory.getLogger(MessageJobService.class);
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

        verify(store).scheduleRetry(eq(claim), any(Duration.class));
        assertThat(result).isEqualTo(MessageJobProcessingResult.OWNERSHIP_LOST);
        assertThat(appender.list).noneMatch(event -> "message_job_retry".equals(keyValue(event, "event")));
    }

    @Test
    void reports_retry_scheduled_only_after_the_claim_guarded_storage_transition_succeeds() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenThrow(ConversationStoreFailure.transientFailure(
                new IllegalStateException("storage unavailable")));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 0)));
        when(store.scheduleRetry(eq(claim), any(Duration.class))).thenReturn(true);
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("Provider must not be called after the storage failure");
        });

        MessageJobProcessingResult result = service(store, model, catalog()).process(claim, () -> true);

        assertThat(result).isEqualTo(MessageJobProcessingResult.RETRY_SCHEDULED);
        verify(store).scheduleRetry(eq(claim), any(Duration.class));
    }

    @Test
    void reports_ownership_lost_when_the_claim_fence_is_stale_before_processing() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            throw new AssertionError("Provider must not be called for a stale claim");
        });
        org.mockito.Mockito.doThrow(new StaleWorkClaimException()).when(store).bindModelRoute(claim, TEST_ROUTE_ID);

        MessageJobProcessingResult result = service(store, model, catalog()).process(claim, () -> true);

        assertThat(result).isEqualTo(MessageJobProcessingResult.OWNERSHIP_LOST);
        verify(store, org.mockito.Mockito.never()).append(any(), any(), any());
    }

    @Test
    void completes_model_unavailable_without_provider_or_tool_calls_when_the_job_route_differs() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        ConversationModel model = mock(ConversationModel.class);
        when(model.routeId()).thenReturn(new ModelRouteId("codex-primary"));
        org.mockito.Mockito.doThrow(new ModelRouteMismatchException()).when(store)
                .bindModelRoute(claim, new ModelRouteId("codex-primary"));

        service(store, model, catalog()).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(store, org.mockito.Mockito.never()).loadHistory(any());
        verify(model, org.mockito.Mockito.never()).respond(any(), any(), any());
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime model is unavailable.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void completes_model_unavailable_without_provider_or_tool_calls_when_a_loaded_continuation_route_differs() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        ConversationModel model = mock(ConversationModel.class);
        ModelRouteId routeId = new ModelRouteId("codex-primary");
        when(model.routeId()).thenReturn(routeId);
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.loadContinuations(claim)).thenReturn(Map.of(new SessionSequence(2),
                new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1})));
        ToolCatalog catalog = () -> { throw new AssertionError("Tool snapshot must not be opened"); };

        service(store, model, catalog).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(model, org.mockito.Mockito.never()).respond(any(), any(), any());
        verify(store, org.mockito.Mockito.never()).reserveModelCall(any(), any(Integer.class), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime model is unavailable.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    @Test
    void completes_model_unavailable_without_tools_or_persisting_a_returned_foreign_continuation() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        ConversationModel model = mock(ConversationModel.class);
        ModelRouteId routeId = new ModelRouteId("codex-primary");
        when(model.routeId()).thenReturn(routeId);
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.empty());
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1});
        when(model.respond(any(), any(), any())).thenAnswer(invocation -> {
            com.java.system.sessionagent.conversation.port.out.ModelCallReservation reservation = invocation.getArgument(1);
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

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(model).respond(any(), any(), any());
        assertThat(toolInvoked).isFalse();
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime model is unavailable.")),
                ConversationStore.JobUpdate.COMPLETE));
        assertThat(batch.getValue().continuation()).isEmpty();
    }

    @Test
    void completes_terminal_continuation_replay_failure_once_without_reserving_or_executing_tools() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> { throw ModelCallFailure.terminal(); });
        AtomicBoolean toolInvoked = new AtomicBoolean();
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(binding("first", arguments -> {
            toolInvoked.set(true);
            return new ToolOutput(false, Map.of());
        })));

        service(store, model, catalog).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        verify(store, org.mockito.Mockito.never()).reserveModelCall(any(), any(Integer.class), any(Instant.class));
        assertThat(toolInvoked).isFalse();
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime model is unavailable.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    private static MessageJobService service(ConversationStore store, ConversationModel model, ToolCatalog catalog) {
        return service(store, model, catalog, 2);
    }

    private static ConversationModel model(ModelRouteId routeId, ModelResponder responder) {
        return new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return routeId;
            }

            @Override
            public ModelCallResult respond(
                    ModelRequest request,
                    ModelCallReservation reservation,
                    Consumer<ModelUsage> usageObserver) {
                return responder.respond(request, reservation, usageObserver);
            }
        };
    }

    private static MessageJobService service(ConversationStore store, ConversationModel model, ToolCatalog catalog, int maximumCalls) {
        return new MessageJobService(store, model, catalog, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), maximumCalls,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    private static ToolCatalog catalog() {
        return () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()), arguments -> new ToolOutput(false, Map.of("value", 1))),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()), arguments -> new ToolOutput(true, Map.of("code", "TOOL_TIMEOUT")))));
    }

    @FunctionalInterface
    private interface ModelResponder {

        ModelCallResult respond(
                ModelRequest request,
                ModelCallReservation reservation,
                Consumer<ModelUsage> usageObserver);
    }

    private static ModelCallResult result(ModelReply reply) {
        return new ModelCallResult(reply, Optional.empty());
    }

    private static ToolSnapshot trackedSnapshot(AtomicInteger releases) {
        return new ToolSnapshot(List.of(), releases::incrementAndGet);
    }

    private static ToolBinding binding(String name, java.util.function.Function<Map<String, Object>, ToolOutput> invocation) {
        return new ToolBinding(new ToolDefinition(new ToolName(name), name, Map.of()), invocation::apply);
    }

    private static ToolRequest request(String id, String name) {
        return new ToolRequest(new ToolCallId(id), new ToolName(name), Map.of());
    }

    private static List<com.java.system.sessionagent.conversation.domain.SessionMessage> nativeHistory() {
        return List.of(user(), new com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage(
                new SessionId("session-1"), new SessionSequence(2), Optional.of(new MessageJobId("job-1")), Instant.EPOCH,
                MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking."), List.of(request("call-1", "first"))),
                new com.java.system.sessionagent.conversation.domain.ToolObservation(new SessionId("session-1"), new SessionSequence(3),
                        Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("snapshot", "first"))));
    }

    private static MessageWorkClaim claim() {
        Instant claimedAt = Instant.parse("2026-09-01T00:00:00Z");
        return new MessageWorkClaim(new MessageJobId("job-1"), new SessionId("session-1"), "worker", 1, claimedAt,
                claimedAt.plusSeconds(30));
    }

    private static Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value).findFirst().orElse(null); // cs-allow Optional test lookup uses null for a missing key
    }

    private static UserMessage user() {
        return new UserMessage(new SessionId("session-1"), new SessionSequence(1), Optional.of(new MessageJobId("job-1")), Instant.EPOCH,
                MessageRole.USER, "alice", "hello");
    }
}
