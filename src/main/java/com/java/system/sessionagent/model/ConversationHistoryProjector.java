package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConversationHistoryProjector {

    public List<Message> project(List<SessionMessage> history) {
        Assert.notNull(history, "Conversation history must not be null");
        List<Message> projected = new ArrayList<>();
        for (SessionMessage message : history) {
            addProjection(projected, message);
        }
        return List.copyOf(projected);
    }

    private static void addProjection(List<Message> projected, SessionMessage message) {
        if (message instanceof UserMessage userMessage) {
            projected.add(org.springframework.ai.chat.messages.UserMessage.builder()
                    .text(userMessage.participantId() + ": " + userMessage.message())
                    .metadata(Map.of("participantId", userMessage.participantId()))
                    .build());
            return;
        }
        if (message instanceof ToolMessage toolMessage) {
            addToolProjection(projected, toolMessage.modelCallId(), toolMessage.toolName(), toolMessage.arguments(),
                    toolMessage.resultJson());
            return;
        }
        if (message instanceof FeedbackMessage feedbackMessage) {
            addFeedbackProjection(projected, feedbackMessage);
            return;
        }
        if (message instanceof AssistantMessage assistantMessage) {
            projected.add(new org.springframework.ai.chat.messages.AssistantMessage(assistantMessage.message()));
            return;
        }
        throw new IllegalArgumentException("Unsupported conversation history message");
    }

    private static void addFeedbackProjection(List<Message> projected, FeedbackMessage feedbackMessage) {
        if (feedbackMessage.modelCallId().isPresent()) {
            addToolProjection(projected,
                    feedbackMessage.modelCallId().orElseThrow(),
                    feedbackMessage.toolName().orElseThrow(),
                    feedbackMessage.rejectedArguments().orElseThrow(),
                    "Tool request was rejected [" + feedbackMessage.code() + "]: " + feedbackMessage.message());
            return;
        }
        projected.add(new org.springframework.ai.chat.messages.UserMessage(
                "Runtime feedback [" + feedbackMessage.code() + "]: " + feedbackMessage.message()));
    }

    private static void addToolProjection(
            List<Message> projected, String callId, String toolName, String arguments, String result) {
        org.springframework.ai.chat.messages.AssistantMessage toolRequest =
                org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                callId, "function", toolName, arguments)))
                        .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, result)))
                .build();
        projected.add(toolRequest);
        projected.add(toolResponse);
    }
}
