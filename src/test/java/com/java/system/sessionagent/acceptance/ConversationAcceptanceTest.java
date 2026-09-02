package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAcceptanceTest {

    @Test
    void native_call_history_keeps_arguments_only_on_the_assistant_event() {
        ToolRequest request = new ToolRequest(new ToolCallId("call-1"), new ToolName("mcp_lookup"), Map.of("query", "fees"));
        AssistantToolCallsMessage calls = new AssistantToolCallsMessage(new SessionId("session-1"), new SessionSequence(2),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS,
                Optional.of("I will inspect it."), List.of(request));

        assertThat(calls.requests()).containsExactly(request);
        assertThat(calls.requests().getFirst().arguments()).containsEntry("query", "fees");
    }
}
