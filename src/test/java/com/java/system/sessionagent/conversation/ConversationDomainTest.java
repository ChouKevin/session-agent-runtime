package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationDomainTest {
    @Test void final_model_request_contains_only_history_and_tool_snapshot() {
        UserMessage message = new UserMessage(new SessionId("session"), new SessionSequence(1), Optional.empty(), Instant.now(), MessageRole.USER, "alice", "hello");
        ModelRequest request = new ModelRequest(List.of(message), new DirectToolRegistry(List.of()).snapshot());
        assertThat(request.history()).containsExactly(message);
    }
}
