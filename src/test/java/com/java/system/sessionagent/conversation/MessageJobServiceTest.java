package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobServiceTest {

    @Test
    void commits_native_assistant_calls_and_ordered_outputs_as_one_batch() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()), List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(2), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        ConversationModel model = (request, reservation, usage) -> {
            reservation.reserve();
            return request.history().size() == 1
                    ? new ModelReply.UseTools(Optional.of("Checking."), List.of(
                    new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of("query", "fees")),
                    new ToolRequest(new ToolCallId("call-2"), new ToolName("second"), Map.of())))
                    : new ModelReply.Text("Done");
        };

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
    void completes_invalid_history_without_a_reservation_or_reclaimable_work() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        ConversationModel model = (request, reservation, usage) -> { throw ModelCallFailure.invalidHistory(); };

        service(store, model, catalog()).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue()).isEqualTo(new ConversationStore.MessageBatch(List.of(
                new ConversationStore.RuntimeData("INVALID_CONVERSATION_HISTORY", "Runtime conversation history is invalid.")),
                ConversationStore.JobUpdate.COMPLETE));
    }

    private static MessageJobService service(ConversationStore store, ConversationModel model, ToolCatalog catalog) {
        return new MessageJobService(store, model, catalog, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    private static ToolCatalog catalog() {
        return () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()), arguments -> new ToolOutput(false, Map.of("value", 1))),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()), arguments -> new ToolOutput(true, Map.of("code", "TOOL_TIMEOUT")))));
    }

    private static MessageWorkClaim claim() {
        Instant claimedAt = Instant.parse("2026-09-01T00:00:00Z");
        return new MessageWorkClaim(new MessageJobId("job-1"), new SessionId("session-1"), "worker", 1, claimedAt,
                claimedAt.plusSeconds(30));
    }

    private static UserMessage user() {
        return new UserMessage(new SessionId("session-1"), new SessionSequence(1), Optional.of(new MessageJobId("job-1")), Instant.EPOCH,
                MessageRole.USER, "alice", "hello");
    }
}
