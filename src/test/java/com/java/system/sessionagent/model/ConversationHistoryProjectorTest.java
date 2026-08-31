package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ObservationId;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationHistoryProjectorTest {

    private static final SessionId SESSION_ID = new SessionId("session-1");
    private static final MessageJobId JOB_ID = new MessageJobId("job-1");
    private static final Instant CREATED_AT = Instant.parse("2026-08-15T10:15:30Z");

    @Test
    void projects_the_full_ordered_durable_history_with_neutral_spring_ai_messages() {
        ConversationHistoryProjector projector = new ConversationHistoryProjector();

        List<Message> projected = projector.project(history());

        assertThat(projected).hasSize(4);
        assertThat(projected.get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(projected.get(0).getText()).isEqualTo("Alice: What repositories are available?");
        assertThat(projected.get(1)).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        assertThat(projected.get(1).getText()).isEqualTo("The catalog is available.");
        assertThat(projected.get(2)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(projected.get(2).getText()).isEqualTo("""
                Runtime tool observation
                Tool: list_repositories
                Input:
                {"query":"repositories"}
                Output:
                {"repositories":[]}
                End runtime tool observation
                """);
        assertThat(projected.get(3)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(projected.get(3).getText()).isEqualTo("""
                Runtime message
                Code: MODEL_OUTPUT_INVALID
                Message: Return one tool call or one reply.
                End runtime message
                """);
    }

    private static List<SessionMessage> history() {
        return List.of(
                new UserMessage(SESSION_ID, new SessionSequence(1), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.USER, "Alice", "What repositories are available?"),
                new AssistantMessage(SESSION_ID, new SessionSequence(2), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.ASSISTANT, "The catalog is available."),
                new ToolObservation(SESSION_ID, new SessionSequence(3), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.TOOL, new ObservationId("observation-1"), "list_repositories",
                        "{\"query\":\"repositories\"}", "{\"repositories\":[]}"),
                new RuntimeMessage(SESSION_ID, new SessionSequence(4), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.RUNTIME, "MODEL_OUTPUT_INVALID", "Return one tool call or one reply."));
    }
}
