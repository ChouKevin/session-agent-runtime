package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class ConversationHistoryProjector {

    private static final String THOUGHT_SIGNATURES = "thoughtSignatures";

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
        if (message instanceof AssistantMessage assistantMessage) {
            projected.add(new org.springframework.ai.chat.messages.AssistantMessage(assistantMessage.message()));
            return;
        }
        if (message instanceof ToolObservation observation) {
            projected.add(new org.springframework.ai.chat.messages.UserMessage(toolObservationText(observation)));
            return;
        }
        if (message instanceof ToolMessage toolMessage) {
            addLegacyToolProjection(projected, toolMessage);
            return;
        }
        if (message instanceof RuntimeMessage runtimeMessage) {
            projected.add(new org.springframework.ai.chat.messages.UserMessage(runtimeMessageText(runtimeMessage)));
            return;
        }
        throw new IllegalArgumentException("Unsupported conversation history message");
    }

    private static void addLegacyToolProjection(List<Message> projected, ToolMessage toolMessage) {
        org.springframework.ai.chat.messages.AssistantMessage toolRequest =
                org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("")
                        .properties(Map.of(THOUGHT_SIGNATURES,
                                List.of(Base64.getDecoder().decode(toolMessage.modelContext()))))
                        .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                                toolMessage.modelCallId(), "function", toolMessage.toolName(), toolMessage.arguments())))
                        .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        toolMessage.modelCallId(), toolMessage.toolName(), toolMessage.resultJson())))
                .build();
        projected.add(toolRequest);
        projected.add(toolResponse);
    }

    private static String toolObservationText(ToolObservation observation) {
        return """
                Runtime tool observation
                Tool: %s
                Input:
                %s
                Output:
                %s
                End runtime tool observation
                """.formatted(observation.toolName(), observation.input(), observation.output());
    }

    private static String runtimeMessageText(RuntimeMessage runtimeMessage) {
        return """
                Runtime message
                Code: %s
                Message: %s
                End runtime message
                """.formatted(runtimeMessage.code(), runtimeMessage.message());
    }
}
