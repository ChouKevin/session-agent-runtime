package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.JobControl;
import com.java.system.sessionagent.conversation.domain.IntentRequest;
import com.java.system.sessionagent.conversation.domain.IntentResult;
import com.java.system.sessionagent.conversation.domain.JobStatus;
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
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class MessageJobTestFixtures {

    static final ModelRouteId TEST_ROUTE_ID = new ModelRouteId("test");

    static ConversationStore mockStore() {
        ConversationStore store = mock(ConversationStore.class);
        when(store.loadJobControl(any())).thenReturn(new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.EXECUTION,
                user(), Optional.empty(), 0, false, false));
        when(store.enterWrapUp(any(), any())).thenReturn(new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.WRAP_UP,
                user(), Optional.empty(), 0, false, false));
        when(store.append(any(), any(), any())).thenAnswer(invocation -> {
            ConversationStore.MessageBatch batch = invocation.getArgument(1);
            return appendedStatus(batch);
        });
        return store;
    }

    static ConversationHistory contextHistory(List<SessionMessage> messages) {
        return new ConversationHistory(messages, Map.of());
    }

    static JobStatus appendedStatus(ConversationStore.MessageBatch batch) {
        return switch (batch.jobUpdate()) {
            case KEEP_WORKING -> JobStatus.WORKING;
            case COMPLETE -> JobStatus.DONE;
            case CANCEL -> JobStatus.CANCELLED;
        };
    }

    static JobProcessor service(ConversationStore store, ConversationModel model, ToolCatalog catalog) {
        return service(store, model, catalog, 2);
    }

    static ConversationModel model(ModelRouteId routeId, ModelResponder responder) {
        return model(routeId, (request, reservation) -> { throw new AssertionError("Unexpected intent request"); }, responder);
    }

    static ConversationModel model(ModelRouteId routeId, IntentResponder intentResponder, ModelResponder responder) {
        return new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return routeId;
            }
            @Override
            public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation) {
                return intentResponder.classify(request, reservation);
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
        return new JobProcessor(store, TestConfiguredModels.withModel(model), catalog, new FileSystemSkillLibrary(new SkillProperties()), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), maximumCalls,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    static ToolCatalog catalog() {
        return () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()), (context, arguments) -> new ToolOutput(false, Map.of("value", 1)), ToolCapability.ORDINARY),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()), (context, arguments) -> new ToolOutput(true, Map.of("code", "TOOL_TIMEOUT")), ToolCapability.ORDINARY)));
    }

    static ModelCallResult result(ModelReply reply) {
        return new ModelCallResult(reply, Optional.empty());
    }

    static ToolBinding binding(String name, Function<Map<String, Object>, ToolOutput> invocation) {
        return new ToolBinding(new ToolDefinition(new ToolName(name), name, Map.of()), (context, arguments) -> invocation.apply(arguments), ToolCapability.ORDINARY);
    }

    static ToolRequest request(String id, String name) {
        return new ToolRequest(new ToolCallId(id), new ToolName(name), Map.of());
    }

    static MessageWorkClaim claim() {
        Instant claimedAt = Instant.parse("2026-09-01T00:00:00Z");
        return new MessageWorkClaim(new MessageJobId("00000000-0000-0000-0000-000000000001"), new SessionId("00000000-0000-0000-0000-000000000002"),
                new com.java.system.sessionagent.conversation.domain.ModelSelection(TEST_ROUTE_ID.value(), "v1:test"), "worker", 1, claimedAt,
                claimedAt.plusSeconds(30));
    }

    static UserMessage user() {
        return new UserMessage(new SessionId("00000000-0000-0000-0000-000000000002"), new SessionSequence(1), Optional.of(new MessageJobId("00000000-0000-0000-0000-000000000001")), Instant.EPOCH,
                MessageRole.USER, "alice", "hello");
    }

    static void assertTerminalFailure(ConversationStore.MessageBatch batch, String code) {
        assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
        assertThat(batch.failure().map(Enum::name)).contains(code);
        assertThat(batch.messages()).singleElement().isInstanceOfSatisfying(ConversationStore.RuntimeData.class,
                event -> assertThat(event.code()).isEqualTo(code));
    }

    interface IntentResponder {
        IntentResult classify(IntentRequest request, ModelCallReservation reservation);
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
