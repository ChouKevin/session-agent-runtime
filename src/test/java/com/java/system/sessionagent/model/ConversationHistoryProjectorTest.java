package com.java.system.sessionagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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

    @ParameterizedTest(name = "rejects {0} native history before provider invocation")
    @MethodSource("invalidHistories")
    void rejects_invalid_native_history_before_provider_invocation(String ignoredCase, List<SessionMessage> history) {
        assertThatThrownBy(() -> new ConversationHistoryProjector(new ObjectMapper()).project(history))
                .isInstanceOf(InvalidConversationHistoryException.class);
    }

    private static Stream<Arguments> invalidHistories() {
        List<SessionMessage> valid = batch();
        AssistantToolCallsMessage duplicate = new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(1),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.empty(), List.of(
                new ToolRequest(new ToolCallId("call-1"), new ToolName("first"), Map.of()),
                new ToolRequest(new ToolCallId("call-1"), new ToolName("second"), Map.of())));
        ToolObservation mismatchedName = new ToolObservation(new SessionId("session-1"), new SessionSequence(2),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.TOOL,
                new ToolCallId("call-1"), "other", Map.of("isError", false, "result", Map.of()));
        ToolObservation extraResult = new ToolObservation(new SessionId("session-1"), new SessionSequence(4),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.TOOL,
                new ToolCallId("call-extra"), "extra", Map.of("isError", false, "result", Map.of()));
        ToolObservation crossJob = new ToolObservation(new SessionId("session-1"), new SessionSequence(2),
                Optional.of(new MessageJobId("job-2")), Instant.EPOCH, MessageRole.TOOL,
                new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of()));
        ToolObservation crossSession = new ToolObservation(new SessionId("session-2"), new SessionSequence(2),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.TOOL,
                new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of()));
        ToolObservation nonconsecutive = new ToolObservation(new SessionId("session-1"), new SessionSequence(4),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.TOOL,
                new ToolCallId("call-1"), "first", Map.of("isError", false, "result", Map.of()));
        AssistantMessage interleaved = new AssistantMessage(new SessionId("session-1"), new SessionSequence(2),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.ASSISTANT, "interleaved");
        return Stream.of(
                Arguments.of("orphan TOOL", List.of(valid.get(1))),
                Arguments.of("missing TOOL", List.of(valid.getFirst(), valid.get(1))),
                Arguments.of("duplicate call ID", List.of(duplicate, valid.get(1), valid.get(2))),
                Arguments.of("name mismatch", List.of(valid.getFirst(), mismatchedName, valid.get(2))),
                Arguments.of("extra result", List.of(valid.getFirst(), valid.get(1), valid.get(2), extraResult)),
                Arguments.of("cross-job TOOL", List.of(valid.getFirst(), crossJob, valid.get(2))),
                Arguments.of("cross-session TOOL", List.of(valid.getFirst(), crossSession, valid.get(2))),
                Arguments.of("nonconsecutive TOOL sequence", List.of(valid.getFirst(), nonconsecutive, valid.get(2))),
                Arguments.of("interleaved incomplete batch", List.of(valid.getFirst(), interleaved, valid.get(2))));
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
