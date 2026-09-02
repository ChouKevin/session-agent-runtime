package com.java.system.sessionagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationHistoryProjectorTest {
    @Test
    void replays_a_native_call_batch_as_one_assistant_and_one_ordered_tool_response_message() {
        List<Message> messages = new ConversationHistoryProjector(new ObjectMapper()).project(batch());

        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst()).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        assertThat(messages.get(1)).isInstanceOfSatisfying(ToolResponseMessage.class,
                value -> assertThat(value.getResponses()).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("call-1", "call-2"));
    }

    @Test
    void rejects_an_incomplete_tool_batch_before_provider_invocation() {
        assertThatThrownBy(() -> new ConversationHistoryProjector(new ObjectMapper()).project(List.of(batch().getFirst())))
                .isInstanceOf(InvalidConversationHistoryException.class);
    }

    @Test
    void rejects_duplicate_call_ids_in_a_durable_batch() {
        List<SessionMessage> history = batch();
        AssistantToolCallsMessage duplicate = new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(1),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.empty(), List.of(
                new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of()),
                new ToolRequest(new ToolCallId("call-1"), new ToolName("second"), Map.of())));
        assertThatThrownBy(() -> new ConversationHistoryProjector(new ObjectMapper()).project(List.of(duplicate, history.get(1), history.get(2))))
                .isInstanceOf(InvalidConversationHistoryException.class);
    }

    private static List<SessionMessage> batch() {
        SessionId sessionId = new SessionId("session-1");
        MessageJobId jobId = new MessageJobId("job-1");
        Instant now = Instant.EPOCH;
        return List.of(
                new AssistantToolCallsMessage(sessionId, new SessionSequence(1), Optional.of(jobId), now, MessageRole.ASSISTANT_TOOL_CALLS,
                        Optional.of("Checking."), List.of(
                        new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of("query", "fees")),
                        new ToolRequest(new ToolCallId("call-2"), new ToolName("second"), Map.of()))),
                new ToolObservation(sessionId, new SessionSequence(2), Optional.of(jobId), now, MessageRole.TOOL,
                        new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of())),
                new ToolObservation(sessionId, new SessionSequence(3), Optional.of(jobId), now, MessageRole.TOOL,
                        new ToolCallId("call-2"), "second", Map.of("isError", false, "result", Map.of())));
    }
}
