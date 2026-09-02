package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiParticipantConversationTest {

    @Test
    void retains_participant_attribution_in_shared_history() {
        SessionId sessionId = new SessionId("shared-session");
        List<UserMessage> history = List.of(
                new UserMessage(sessionId, new SessionSequence(1), Optional.of(new MessageJobId("job-1")), Instant.EPOCH,
                        MessageRole.USER, "alice", "Can you clarify this?"),
                new UserMessage(sessionId, new SessionSequence(2), Optional.of(new MessageJobId("job-2")), Instant.EPOCH,
                        MessageRole.USER, "bob", "Which method is supported?"));

        assertThat(history).extracting(UserMessage::participantId).containsExactly("alice", "bob");
    }
}
