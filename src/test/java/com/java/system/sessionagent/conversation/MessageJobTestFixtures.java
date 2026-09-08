package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

final class MessageJobTestFixtures {

    static final ModelRouteId TEST_ROUTE_ID = new ModelRouteId("test");

    static JobProcessor service(ConversationStore store, ConversationModel model, ToolCatalog catalog) {
        return service(store, model, catalog, 2);
    }

    static ConversationModel model(ModelRouteId routeId, ModelResponder responder) {
        return new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return routeId;
            }

            @Override
            public ModelCallResult respond(
                    ModelRequest request,
                    ModelCallReservation reservation,
                    Consumer<ModelUsage> usageObserver) {
                return responder.respond(request, reservation, usageObserver);
            }
        };
    }

    static JobProcessor service(ConversationStore store, ConversationModel model, ToolCatalog catalog, int maximumCalls) {
        return new JobProcessor(store, TestConfiguredModels.withModel(model), catalog, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), maximumCalls,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    static ToolCatalog catalog() {
        return () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()), arguments -> new ToolOutput(false, Map.of("value", 1))),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()), arguments -> new ToolOutput(true, Map.of("code", "TOOL_TIMEOUT")))));
    }

    static ModelCallResult result(ModelReply reply) {
        return new ModelCallResult(reply, Optional.empty());
    }

    static ToolBinding binding(String name, Function<Map<String, Object>, ToolOutput> invocation) {
        return new ToolBinding(new ToolDefinition(new ToolName(name), name, Map.of()), invocation::apply);
    }

    static ToolRequest request(String id, String name) {
        return new ToolRequest(new ToolCallId(id), new ToolName(name), Map.of());
    }

    static MessageWorkClaim claim() {
        Instant claimedAt = Instant.parse("2026-09-01T00:00:00Z");
        return new MessageWorkClaim(new MessageJobId("job-1"), new SessionId("session-1"),
                new com.java.system.sessionagent.conversation.domain.ModelSelection(TEST_ROUTE_ID.value(), "v1:test"), "worker", 1, claimedAt,
                claimedAt.plusSeconds(30));
    }

    static UserMessage user() {
        return new UserMessage(new SessionId("session-1"), new SessionSequence(1), Optional.of(new MessageJobId("job-1")), Instant.EPOCH,
                MessageRole.USER, "alice", "hello");
    }

    interface ModelResponder {

        ModelCallResult respond(
                ModelRequest request,
                ModelCallReservation reservation,
                Consumer<ModelUsage> usageObserver);
    }

    private MessageJobTestFixtures() {
    }
}
