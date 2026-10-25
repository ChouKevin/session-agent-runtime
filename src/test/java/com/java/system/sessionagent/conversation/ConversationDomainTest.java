package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationDomainTest {
    @Test void final_model_request_contains_only_history_and_tool_snapshot() {
        UserMessage message = new UserMessage(new SessionId("session"), new SessionSequence(1), Optional.empty(), Instant.now(), MessageRole.USER, "alice", "hello");
        ModelRequest request = new ModelRequest(List.of(message), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER);
        assertThat(request.history()).containsExactly(message);
    }

    @Test
    void native_tool_request_keeps_the_call_id_and_generic_arguments_together() {
        ToolRequest request = new ToolRequest(new ToolCallId("call-1"),
                new ToolName("semantic_search_code"), Map.of("query", "fees"));

        assertThat(request.toolCallId().value()).isEqualTo("call-1");
        assertThat(request.arguments()).containsExactly(Map.entry("query", "fees"));
    }

    @Test
    void assistant_tool_calls_preserve_tool_request_arguments() {
        ToolRequest request = new ToolRequest(new ToolCallId("call-1"),
                new ToolName("mcp_lookup"), Map.of("query", "fees"));
        AssistantToolCallsMessage calls = new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(2),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS,
                Optional.of("I will inspect it."), List.of(request));

        assertThat(calls.requests()).containsExactly(request);
        assertThat(calls.requests().getFirst().arguments()).containsEntry("query", "fees");
    }

    @Test
    void user_messages_preserve_participant_attribution() {
        SessionId sessionId = new SessionId("shared-session");
        List<UserMessage> history = List.of(
                new UserMessage(sessionId, new SessionSequence(1), Optional.of(new MessageJobId("job-1")), Instant.EPOCH,
                        MessageRole.USER, "alice", "Can you clarify this?"),
                new UserMessage(sessionId, new SessionSequence(2), Optional.of(new MessageJobId("job-2")), Instant.EPOCH,
                        MessageRole.USER, "bob", "Which method is supported?"));

        assertThat(history).extracting(message -> message.participantId()).containsExactly("alice", "bob");
    }
}
