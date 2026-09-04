package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
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
        ModelRequest request = new ModelRequest(List.of(message), new ToolSnapshot(List.of()));
        assertThat(request.history()).containsExactly(message);
    }

    @Test
    void native_tool_request_keeps_the_call_id_and_generic_arguments_together() {
        ToolRequest request = new ToolRequest(new ToolCallId("call-1"),
                new com.java.system.sessionagent.tool.domain.ToolName("semantic_search_code"), Map.of("query", "fees"));

        assertThat(request.toolCallId().value()).isEqualTo("call-1");
        assertThat(request.arguments()).containsExactly(Map.entry("query", "fees"));
    }
}
