package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiParticipantConversationTest {

    @Test
    void gives_a_second_participant_the_unanswered_clarification_and_prior_observations() {
        try (ConversationAcceptanceTest.AcceptanceRuntime runtime = new ConversationAcceptanceTest.AcceptanceRuntime(List.of(
                new ModelReply.UseTools(Optional.of("I will inspect the available payment sources."), List.of(
                        ConversationAcceptanceTest.tool("list_repositories", "{}"),
                        ConversationAcceptanceTest.tool("codebase_list_entry_points", ConversationAcceptanceTest.paymentEntryPointsInput()))),
                ConversationAcceptanceTest.text("Which payment method needs clarification?"),
                ConversationAcceptanceTest.text("Credit card, bank transfer, and wallet are supported.")))) {
            MessageReceipt alice = runtime.receive("shared-session", "alice", "alice-1", "Can you clarify the payment methods?");
            runtime.process(alice);
            MessageReceipt bob = runtime.receive("shared-session", "bob", "bob-1", "Which payment methods are supported?");
            runtime.process(bob);

            ModelRequest bobRequest = runtime.modelRequests().getLast();
            assertThat(bobRequest.history()).extracting(SessionMessage::role).containsExactly(
                    MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.TOOL, MessageRole.ASSISTANT, MessageRole.USER);
            assertThat(bobRequest.history()).filteredOn(UserMessage.class::isInstance).extracting(UserMessage.class::cast)
                    .extracting(UserMessage::participantId).containsExactly("alice", "bob");
            assertThat(bobRequest.history()).filteredOn(UserMessage.class::isInstance).extracting(UserMessage.class::cast)
                    .extracting(UserMessage::message).containsExactly("Can you clarify the payment methods?", "Which payment methods are supported?");
            assertThat(bobRequest.history()).filteredOn(ToolObservation.class::isInstance).extracting(ToolObservation.class::cast)
                    .extracting(ToolObservation::output).allSatisfy(output -> assertThat(output).isNotBlank());
            assertThat(bobRequest.history()).filteredOn(message -> message.role() == MessageRole.ASSISTANT)
                    .extracting(message -> ((com.java.system.sessionagent.conversation.domain.AssistantMessage) message).message())
                    .contains("Which payment method needs clarification?");
            assertThat(runtime.reply(bob).message()).contains("Credit card");
        }
    }
}
