package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.tool.json.StrictJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationHistoryProjectorTest {

    private static final SessionId SESSION_ID = new SessionId("session-1");
    private static final MessageJobId JOB_ID = new MessageJobId("job-1");
    private static final Instant CREATED_AT = Instant.parse("2026-08-15T10:15:30Z");
    private static final byte[] THOUGHT_SIGNATURE = new byte[]{1, 2, 3, 4};
    private static final String MODEL_CONTEXT = Base64.getEncoder().encodeToString(THOUGHT_SIGNATURE);

    @Test
    void projects_the_entire_ordered_history_without_summarizing_or_filtering() {
        ConversationHistoryProjector projector = new ConversationHistoryProjector();

        List<Message> projected = projector.project(history());

        assertThat(projected).hasSize(10);
        assertThat(projected.get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(projected.get(0).getText()).isEqualTo("Alice: What repositories are available?");
        assertThat(projected.get(1)).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        assertThat(projected.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(projected.get(3)).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        assertThat(projected.get(4)).isInstanceOf(ToolResponseMessage.class);
        assertThat(projected.get(5)).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        assertThat(projected.get(6)).isInstanceOf(ToolResponseMessage.class);
        assertThat(projected.get(7)).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
        assertThat(projected.get(7).getText()).isEqualTo("The catalog is available.");
        assertThat(projected.get(8)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(projected.get(8).getText()).isEqualTo("Bob: Please inspect the payment code.");
        assertThat(projected.get(9)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
        assertThat(projected.get(9).getText()).contains("Runtime feedback [MODEL_OUTPUT_INVALID]");

        org.springframework.ai.chat.messages.AssistantMessage catalogRequest =
                (org.springframework.ai.chat.messages.AssistantMessage) projected.get(1);
        ToolResponseMessage catalogResponse = (ToolResponseMessage) projected.get(2);
        assertThat(catalogRequest.getToolCalls()).containsExactly(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        "call-catalog", "function", "list_repositories", "{}"));
        assertThat(catalogRequest.getMetadata().get("thoughtSignatures"))
                .isInstanceOfSatisfying(List.class, signatures -> assertThat((byte[]) signatures.getFirst())
                        .containsExactly(THOUGHT_SIGNATURE));
        assertThat(catalogResponse.getResponses()).containsExactly(
                new ToolResponseMessage.ToolResponse("call-catalog", "list_repositories",
                        "{\"resultId\":\"result-catalog\",\"toolName\":\"list_repositories\",\"data\":{\"repositories\":[]}}"));

        org.springframework.ai.chat.messages.AssistantMessage sourceRequest =
                (org.springframework.ai.chat.messages.AssistantMessage) projected.get(3);
        ToolResponseMessage sourceResponse = (ToolResponseMessage) projected.get(4);
        assertThat(sourceRequest.getToolCalls()).containsExactly(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        "call-source-success", "function", "get_source_segment", "{\"repositoryId\":\"payment-service\"}"));
        assertThat(sourceResponse.getResponses()).containsExactly(
                new ToolResponseMessage.ToolResponse(
                        "call-source-success", "get_source_segment",
                        "{\"resultId\":\"result-source\",\"toolName\":\"get_source_segment\",\"repositoryId\":\"payment-service\",\"revision\":\"revision-1\",\"data\":{\"source\":\"class Payment\"}}"));

        org.springframework.ai.chat.messages.AssistantMessage rejectedRequest =
                (org.springframework.ai.chat.messages.AssistantMessage) projected.get(5);
        ToolResponseMessage rejectedResponse = (ToolResponseMessage) projected.get(6);
        assertThat(rejectedRequest.getToolCalls()).containsExactly(
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        "call-source", "function", "get_source_segment", "{\"repositoryId\":\"payment-service\"}"));
        assertThat(rejectedResponse.getResponses().getFirst().id()).isEqualTo("call-source");
        assertThat(rejectedResponse.getResponses().getFirst().name()).isEqualTo("get_source_segment");
        RejectedToolResponse rejectedToolResponse = new StrictJsonCodec().decode(
                rejectedResponse.getResponses().getFirst().responseData(), RejectedToolResponse.class);
        assertThat(rejectedToolResponse).isEqualTo(new RejectedToolResponse(
                "TOOL_INPUT_INVALID", "Use a repository returned by the catalog.", "REJECTED"));
    }

    private static List<SessionMessage> history() {
        return List.of(
                new UserMessage(SESSION_ID, new SessionSequence(1), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.USER, "Alice", "What repositories are available?"),
                new ToolMessage(SESSION_ID, new SessionSequence(2), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.TOOL, new ResultId("result-catalog"), "call-catalog", MODEL_CONTEXT,
                        "list_repositories", "1",
                        "{}", Optional.empty(), Optional.empty(),
                        "{\"resultId\":\"result-catalog\",\"toolName\":\"list_repositories\",\"data\":{\"repositories\":[]}}", false),
                new ToolMessage(SESSION_ID, new SessionSequence(3), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.TOOL, new ResultId("result-source"), "call-source-success", MODEL_CONTEXT,
                        "get_source_segment", "1",
                        "{\"repositoryId\":\"payment-service\"}", Optional.of("payment-service"), Optional.of("revision-1"),
                        "{\"resultId\":\"result-source\",\"toolName\":\"get_source_segment\",\"repositoryId\":\"payment-service\",\"revision\":\"revision-1\",\"data\":{\"source\":\"class Payment\"}}", true),
                new FeedbackMessage(SESSION_ID, new SessionSequence(4), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.FEEDBACK, "TOOL_INPUT_INVALID", "Use a repository returned by the catalog.", false,
                        Optional.of("call-source"), Optional.of("get_source_segment"),
                        Optional.of("{\"repositoryId\":\"payment-service\"}"), Optional.of(MODEL_CONTEXT)),
                new AssistantMessage(SESSION_ID, new SessionSequence(5), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.ASSISTANT, "The catalog is available.", List.of(new ResultId("result-catalog"))),
                new UserMessage(SESSION_ID, new SessionSequence(6), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.USER, "Bob", "Please inspect the payment code."),
                new FeedbackMessage(SESSION_ID, new SessionSequence(7), Optional.of(JOB_ID), CREATED_AT,
                        MessageRole.FEEDBACK, "MODEL_OUTPUT_INVALID", "Return one tool call or one reply.", false,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private record RejectedToolResponse(String code, String message, String status) {
    }
}
