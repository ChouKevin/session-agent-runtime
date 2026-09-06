package com.java.system.sessionagent.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.java.system.sessionagent.conversation.port.in.SessionDetailView;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageJobView;

import java.time.Instant;
import java.util.Optional;

public final class MessageResponses {

    private MessageResponses() {
    }

    public record SendMessageResponse(String sessionId, String messageJobId) {
    }

    public record SessionLookupResponse(String sessionId, String sessionDetailPath) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionDetailResponse(
            String sessionId,
            Instant createdAt,
            SlackBindingResponse slack,
            CurrentJobSummaryResponse currentJob,
            CompactionBoundaryResponse latestCompaction,
            ContextUsageResponse context) {

        static SessionDetailResponse from(SessionDetailView view, Optional<SlackBindingResponse> slack) {
            return new SessionDetailResponse(view.sessionId(), view.createdAt(), slack.orElse(null),
                    view.currentJob().map(CurrentJobSummaryResponse::from).orElse(null),
                    view.latestCompaction().map(CompactionBoundaryResponse::from).orElse(null), ContextUsageResponse.from(view.context()));
        }
    }

    public record SlackBindingResponse(String teamId, String channelId, String rootThreadTs, Instant createdAt) {
    }

    public record CompactionBoundaryResponse(long generation, long coveredThrough, String reason, Instant createdAt) {
        static CompactionBoundaryResponse from(SessionDetailView.CompactionBoundaryView view) {
            return new CompactionBoundaryResponse(view.generation(), view.coveredThrough(), view.reason(), view.createdAt());
        }
    }

    public record ContextUsageResponse(String modelId, long capacityTokens, long estimatedUsedTokens, double ratio, String basis) {
        static ContextUsageResponse from(SessionDetailView.ContextUsageView view) {
            return new ContextUsageResponse(view.modelId(), view.capacityTokens(), view.estimatedUsedTokens(), view.ratio(),
                    view.basis().name());
        }
    }

    public record CurrentJobSummaryResponse(String messageJobId, String status, int modelCallCount) {
        static CurrentJobSummaryResponse from(MessageJobView view) {
            return new CurrentJobSummaryResponse(view.messageJobId(), view.status().name(), view.modelCallCount());
        }
    }

    public record MessageJobResponse(
            String messageJobId,
            String sessionId,
            String status,
            int retryCount,
            int modelCallCount) {
        static MessageJobResponse from(MessageJobView view) {
            return new MessageJobResponse(view.messageJobId(), view.sessionId(), view.status().name(), view.retryCount(),
                    view.modelCallCount());
        }
    }

    public sealed interface SessionMessageResponse permits UserResponse, AssistantToolCallsResponse, ToolResponse, AssistantResponse, RuntimeResponse {

        long sequence();

        Instant createdAt();

        @JsonProperty("type")
        String type();

        String messageJobId();

        static SessionMessageResponse from(SessionMessage message) {
            String messageJobId = message.messageJobId().map(value -> value.value()).orElse(null); // cs-allow public API represents an absent optional job ID as null
            if (message instanceof UserMessage user) {
                return new UserResponse(user.sequence().value(), user.createdAt(), messageJobId, user.participantId(), user.message());
            }
            if (message instanceof ToolObservation tool) {
                return new ToolResponse(tool.sequence().value(), tool.createdAt(), messageJobId, tool.toolCallId().value(),
                        tool.toolName(), tool.output());
            }
            if (message instanceof AssistantToolCallsMessage calls) {
                return new AssistantToolCallsResponse(calls.sequence().value(), calls.createdAt(), messageJobId,
                        calls.message().orElse(null), calls.requests().stream()
                                .map(request -> new ToolCallResponse(request.toolCallId().value(), request.toolName().value(), request.arguments()))
                                .toList()); // cs-allow public API represents absent optional assistant text as null
            }
            if (message instanceof AssistantMessage assistant) {
                return new AssistantResponse(assistant.sequence().value(), assistant.createdAt(), messageJobId, assistant.message());
            }
            RuntimeMessage runtime = (RuntimeMessage) message;
            return new RuntimeResponse(runtime.sequence().value(), runtime.createdAt(), messageJobId, runtime.code(), runtime.message());
        }
    }

    public record UserResponse(long sequence, Instant createdAt, String messageJobId, String participantId, String message)
            implements SessionMessageResponse {
        @Override public String type() { return "USER"; }
    }

    public record AssistantToolCallsResponse(long sequence, Instant createdAt, String messageJobId, String message,
                                             java.util.List<ToolCallResponse> calls) implements SessionMessageResponse {
        @Override public String type() { return "ASSISTANT_TOOL_CALLS"; }
    }

    public record ToolCallResponse(String toolCallId, String toolName, java.util.Map<String, Object> arguments) {
    }

    public record ToolResponse(long sequence, Instant createdAt, String messageJobId, String toolCallId, String toolName,
                               Object output) implements SessionMessageResponse {
        @Override public String type() { return "TOOL"; }
    }

    public record AssistantResponse(long sequence, Instant createdAt, String messageJobId, String message)
            implements SessionMessageResponse {
        @Override public String type() { return "ASSISTANT"; }
    }

    public record RuntimeResponse(long sequence, Instant createdAt, String messageJobId, String code, String message)
            implements SessionMessageResponse {
        @Override public String type() { return "RUNTIME"; }
    }

    public record ErrorResponse(String code) {
    }
}
