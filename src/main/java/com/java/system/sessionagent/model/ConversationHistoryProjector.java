package com.java.system.sessionagent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConversationHistoryProjector {

    private final ObjectMapper objectMapper;

    public ConversationHistoryProjector(ObjectMapper objectMapper) {
        Assert.notNull(objectMapper, "Object mapper must not be null");
        this.objectMapper = objectMapper;
    }

    public List<Message> project(List<SessionMessage> history) {
        Assert.notNull(history, "Conversation history must not be null");
        List<Message> projected = new ArrayList<>();
        int index = 0;
        while (index < history.size()) {
            SessionMessage message = history.get(index);
            if (message instanceof AssistantToolCallsMessage calls) {
                index = addToolBatch(projected, history, index, calls);
            } else {
                addProjection(projected, message);
                index++;
            }
        }
        return List.copyOf(projected);
    }

    private int addToolBatch(
            List<Message> projected,
            List<SessionMessage> history,
            int assistantIndex,
            AssistantToolCallsMessage calls) {
        List<ToolObservation> observations = new ArrayList<>();
        Set<String> toolCallIds = new HashSet<>();
        for (int offset = 0; offset < calls.requests().size(); offset++) {
            int observationIndex = assistantIndex + offset + 1;
            if (observationIndex >= history.size() || !(history.get(observationIndex) instanceof ToolObservation observation)) {
                throw new InvalidConversationHistoryException();
            }
            ToolRequest request = calls.requests().get(offset);
            if (!toolCallIds.add(request.toolCallId().value())) {
                throw new InvalidConversationHistoryException();
            }
            if (!request.toolCallId().equals(observation.toolCallId()) || !request.toolName().value().equals(observation.toolName())) {
                throw new InvalidConversationHistoryException();
            }
            observations.add(observation);
        }
        List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> toolCalls = calls.requests().stream()
                .map(this::toolCall)
                .toList();
        projected.add(org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content(calls.message().orElse(null)) // cs-allow Spring AI builder accepts absent optional text as null
                .toolCalls(toolCalls)
                .build());
        projected.add(ToolResponseMessage.builder().responses(observations.stream().map(this::toolResponse).toList()).build());
        return assistantIndex + calls.requests().size() + 1;
    }

    private org.springframework.ai.chat.messages.AssistantMessage.ToolCall toolCall(ToolRequest request) {
        return new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(request.toolCallId().value(), "function",
                request.toolName().value(), json(request.arguments()));
    }

    private ToolResponseMessage.ToolResponse toolResponse(ToolObservation observation) {
        return new ToolResponseMessage.ToolResponse(observation.toolCallId().value(), observation.toolName(), json(observation.output()));
    }

    private void addProjection(List<Message> projected, SessionMessage message) {
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
        if (message instanceof RuntimeMessage runtimeMessage) {
            projected.add(new org.springframework.ai.chat.messages.UserMessage("Runtime: " + runtimeMessage.code() + " - "
                    + runtimeMessage.message()));
            return;
        }
        throw new InvalidConversationHistoryException();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new InvalidConversationHistoryException();
        }
    }
}
