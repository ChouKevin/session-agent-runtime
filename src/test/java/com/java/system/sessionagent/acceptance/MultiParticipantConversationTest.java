package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiParticipantConversationTest {

    @Test
    void lets_bob_continue_a_shared_session_without_exposing_alices_pending_input() {
        try (ConversationAcceptanceTest.AcceptanceRuntime runtime = new ConversationAcceptanceTest.AcceptanceRuntime()) {
            runtime.receive("shared-session", "alice", "alice-1", "Can you clarify the payment methods?");
            MessageReceipt bob = runtime.receive("shared-session", "bob", "bob-1", "Which payment methods are supported?");
            runtime.process(bob);

            ModelRequest firstBobRequest = runtime.modelRequests().getFirst();
            List<UserMessage> users = firstBobRequest.history().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).toList();
            assertThat(users).extracting(UserMessage::participantId).containsExactly("bob");
            assertThat(users).extracting(UserMessage::message).containsExactly("Which payment methods are supported?");
            assertThat(runtime.history(bob.sessionId())).noneMatch(message -> message instanceof com.java.system.sessionagent.conversation.domain.FeedbackMessage feedback
                    && feedback.code().equals("WAITING_FOR_USER"));
            assertThat(runtime.reply(bob).citations()).isNotEmpty();
        }
    }
}
