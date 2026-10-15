package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobToolExecutionTest {

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
        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantData("```json\n{\"answer\":\"plain completion\"}\n```")),
                ConversationStore.JobUpdate.COMPLETE));
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

        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
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
        List<ModelRequest> requests = new ArrayList<>();
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
        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
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

        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
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

        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
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

        ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(invoked).isFalse();
        assertTerminalFailure(batch.getValue(), "MODEL_CALL_LIMIT_REACHED");
    }

    @Test
    void uses_the_captured_snapshot_for_a_batch_and_refreshes_before_the_next_native_replay() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<SessionMessage> replayedHistory = nativeHistory();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user())).thenReturn(replayedHistory);
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        ToolSnapshot firstSnapshot = new ToolSnapshot(List.of(binding("first", arguments -> new ToolOutput(false, Map.of("snapshot", "first")))));
        ToolSnapshot refreshedSnapshot = new ToolSnapshot(List.of(binding("second", arguments -> new ToolOutput(false, Map.of("snapshot", "second")))));
        AtomicBoolean firstRead = new AtomicBoolean();
        ToolCatalog catalog = () -> firstRead.compareAndSet(false, true) ? firstSnapshot : refreshedSnapshot;
        List<ModelRequest> requests = new ArrayList<>();
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            requests.add(request);
            reservation.reserve();
            return result(requests.size() == 1
                    ? new ModelReply.UseTools(Optional.of("Checking."), List.of(request("call-1", "first")))
                    : new ModelReply.Text("done"));
        });

        service(store, model, catalog).process(claim, () -> true);

        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        assertThat(batches.getAllValues().getFirst().messages().get(1)).isEqualTo(
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "first", Map.of("isError", false,
                        "result", Map.of("snapshot", "first"))));
        assertThat(requests.get(1).history()).isEqualTo(replayedHistory);
        assertThat(requests.get(1).toolSnapshot()).isEqualTo(refreshedSnapshot);
    }

    private static ToolSnapshot trackedSnapshot(AtomicInteger releases) {
        return new ToolSnapshot(List.of(), releases::incrementAndGet);
    }

    private static List<SessionMessage> nativeHistory() {
        return List.of(user(), new AssistantToolCallsMessage(
                new SessionId("session-1"), new SessionSequence(2), Optional.of(new MessageJobId("job-1")), Instant.EPOCH,
                MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Checking."), List.of(request("call-1", "first"))),
                new ToolObservation(new SessionId("session-1"), new SessionSequence(3),
                        Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.TOOL, new ToolCallId("call-1"), "first",
                        Map.of("isError", false, "result", Map.of("snapshot", "first"))));
    }
}
