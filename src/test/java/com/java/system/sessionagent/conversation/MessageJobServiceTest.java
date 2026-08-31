package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolExecutor;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.json.ToolSchemaFactory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageJobServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void appends_final_assistant_text_and_completes_the_claim() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1));
        ConversationModel model = (request, reservation, usage) -> {
            reservation.reserve();
            usage.accept(new ModelUsage(1, 1, 2, true));
            return new ModelReply.Text("answer");
        };

        service(store, model).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue().messages()).containsExactly(new ConversationStore.AssistantData("answer"));
        assertThat(batch.getValue().jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
    }

    @Test
    void schedules_a_transient_model_failure_without_appending_history() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 0, 1)));
        when(store.scheduleRetry(eq(claim), eq(Duration.ofSeconds(1)))).thenReturn(true);

        service(store, (request, reservation, usage) -> {
            reservation.reserve();
            throw ModelCallFailure.transientFailure();
        }).process(claim, () -> true);

        verify(store).scheduleRetry(claim, Duration.ofSeconds(1));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).append(any(), any(), any());
    }

    @Test
    void persists_mixed_assistant_text_and_ordered_tool_observations_before_the_next_model_call() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        List<SessionMessage> durableHistory = new ArrayList<>(List.of(user()));
        when(store.loadHistory(claim.sessionId())).thenAnswer(invocation -> List.copyOf(durableHistory));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        doAnswer(invocation -> {
            ConversationStore.MessageBatch batch = invocation.getArgument(1, ConversationStore.MessageBatch.class);
            if (batch.jobUpdate() == ConversationStore.JobUpdate.KEEP_WORKING) {
                appendToDurableHistory(claim, durableHistory, batch);
            }
            return null;
        }).when(store).append(eq(claim), any(ConversationStore.MessageBatch.class), any(Instant.class));
        ToolRegistration<ToolInput> first = registration("first", input -> "first output");
        ToolRegistration<ToolInput> second = registration("second", input -> "second output");
        List<ModelRequest> requests = new ArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ConversationModel model = (request, reservation, usage) -> {
            requests.add(request);
            reservation.reserve();
            if (modelCalls.getAndIncrement() == 0) {
                return new ModelReply.UseTools(Optional.of("Checking."), List.of(
                        new ToolRequest(new ToolName("first"), "{\"value\":\"a\"}"),
                        new ToolRequest(new ToolName("second"), "{\"value\":\"b\"}")));
            }
            return new ModelReply.Text("Done");
        };

        service(store, model, List.of(first, second), 3).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batches = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        assertThat(batches.getAllValues().getFirst().messages()).extracting(Object::getClass).containsExactly(
                ConversationStore.AssistantData.class,
                ConversationStore.ToolObservationData.class,
                ConversationStore.ToolObservationData.class);
        assertThat(batches.getAllValues().getFirst().messages().stream()
                .filter(ConversationStore.ToolObservationData.class::isInstance)
                .map(ConversationStore.ToolObservationData.class::cast)
                .toList())
                .extracting(ConversationStore.ToolObservationData::toolName)
                .containsExactly("first", "second");
        assertThat(batches.getAllValues().get(1).messages()).containsExactly(new ConversationStore.AssistantData("Done"));
        assertThat(requests).hasSize(2);
        List<SessionMessage> secondRequestHistory = requests.get(1).history();
        assertThat(secondRequestHistory).extracting(SessionMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.TOOL);
        assertThat(secondRequestHistory).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(secondRequestHistory.get(1)).isInstanceOfSatisfying(AssistantMessage.class,
                assistant -> assertThat(assistant.message()).isEqualTo("Checking."));
        assertThat(secondRequestHistory.get(2)).isInstanceOfSatisfying(ToolObservation.class, observation -> {
            assertThat(observation.toolName()).isEqualTo("first");
            assertThat(observation.input()).isEqualTo("{\"value\":\"a\"}");
            assertThat(observation.output()).isEqualTo("first output");
        });
        assertThat(secondRequestHistory.get(3)).isInstanceOfSatisfying(ToolObservation.class, observation -> {
            assertThat(observation.toolName()).isEqualTo("second");
            assertThat(observation.input()).isEqualTo("{\"value\":\"b\"}");
            assertThat(observation.output()).isEqualTo("second output");
        });
    }

    @Test
    void continues_after_one_tool_fails_and_persists_its_sanitized_observation_before_later_tools() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()), List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1), OptionalInt.of(2));
        AtomicInteger laterCalls = new AtomicInteger();
        ToolRegistration<ToolInput> broken = registration("broken", input -> {
            throw new IllegalStateException("private failure");
        });
        ToolRegistration<ToolInput> later = registration("later", input -> {
            laterCalls.incrementAndGet();
            return "later output";
        });
        ConversationModel model = scriptedModel(
                new ModelReply.UseTools(Optional.empty(), List.of(
                        new ToolRequest(new ToolName("broken"), "{\"value\":\"a\"}"),
                        new ToolRequest(new ToolName("later"), "{\"value\":\"b\"}"))),
                new ModelReply.Text("Done"));

        service(store, model, List.of(broken, later), 3).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batches = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(2)).append(eq(claim), batches.capture(), any(Instant.class));
        List<ConversationStore.ToolObservationData> observations = batches.getAllValues().getFirst().messages().stream()
                .map(ConversationStore.ToolObservationData.class::cast).toList();
        assertThat(laterCalls).hasValue(1);
        assertThat(observations).extracting(ConversationStore.ToolObservationData::toolName).containsExactly("broken", "later");
        assertThat(observations.getFirst().output()).contains("TOOL_RESPONSE_INVALID").doesNotContain("private failure");
    }

    @Test
    void completes_at_the_model_call_limit_without_executing_last_call_tools() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(1), any(Instant.class))).thenReturn(OptionalInt.of(1));
        AtomicInteger executions = new AtomicInteger();
        ConversationModel model = scriptedModel(new ModelReply.UseTools(Optional.of("Partial"),
                List.of(new ToolRequest(new ToolName("lookup"), "{\"value\":\"a\"}"))));

        service(store, model, List.of(registration("lookup", input -> {
            executions.incrementAndGet();
            return "unused";
        })), 1).process(claim, () -> true);

        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(executions).hasValue(0);
        assertThat(batch.getValue().messages()).containsExactly(new ConversationStore.AssistantData("Partial"),
                new ConversationStore.RuntimeData("MODEL_CALL_LIMIT_REACHED", "Runtime model call limit reached."));
    }

    @Test
    void completes_retry_exhaustion_once_without_duplicate_history() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1));
        when(store.readJob(claim.messageJobId())).thenReturn(Optional.of(new ConversationStore.MessageJobProjection(
                claim.messageJobId(), claim.sessionId(), JobStatus.WORKING, 2, 1)));

        service(store, (request, reservation, usage) -> {
            reservation.reserve();
            throw ModelCallFailure.transientFailure();
        }).process(claim, () -> true);

        verify(store, org.mockito.Mockito.never()).scheduleRetry(any(), any());
        org.mockito.ArgumentCaptor<ConversationStore.MessageBatch> batch = org.mockito.ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(store, org.mockito.Mockito.times(1)).append(eq(claim), batch.capture(), any(Instant.class));
        assertThat(batch.getValue().jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
        assertThat(batch.getValue().messages()).containsExactly(
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime model is unavailable."));
    }

    @Test
    void stops_before_appending_when_work_ownership_is_lost_after_a_model_response() {
        ConversationStore store = mock(ConversationStore.class);
        MessageWorkClaim claim = claim();
        AtomicInteger ownedChecks = new AtomicInteger();
        when(store.loadHistory(claim.sessionId())).thenReturn(List.of(user()));
        when(store.reserveModelCall(eq(claim), eq(3), any(Instant.class))).thenReturn(OptionalInt.of(1));

        service(store, (request, reservation, usage) -> {
            reservation.reserve();
            return new ModelReply.Text("answer");
        }).process(claim, () -> ownedChecks.getAndIncrement() == 0);

        verify(store, org.mockito.Mockito.never()).append(any(), any(), any());
    }

    private static MessageJobService service(ConversationStore store, ConversationModel model) {
        return service(store, model, List.of(), 3);
    }

    private static MessageJobService service(ConversationStore store, ConversationModel model,
                                             List<ToolRegistration<?>> registrations, int maximumModelCalls) {
        return new MessageJobService(store, model, new DirectToolRegistry(registrations), Clock.fixed(NOW, ZoneOffset.UTC), maximumModelCalls,
                new MessageJobRetryPolicy(2, Duration.ofSeconds(10)), new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());
    }

    private static ToolRegistration<ToolInput> registration(String name, ToolExecutor<ToolInput> executor) {
        ToolName toolName = new ToolName(name);
        return new ToolRegistration<>(new ToolDefinition(toolName, name, new ToolSchemaFactory().schemaFor(ToolInput.class)), ToolInput.class, executor);
    }

    private static ConversationModel scriptedModel(ModelReply... replies) {
        AtomicInteger index = new AtomicInteger();
        return (request, reservation, usage) -> {
            reservation.reserve();
            return replies[index.getAndIncrement()];
        };
    }

    private static void appendToDurableHistory(MessageWorkClaim claim, List<SessionMessage> history,
                                               ConversationStore.MessageBatch batch) {
        for (ConversationStore.MessageData message : batch.messages()) {
            SessionSequence sequence = new SessionSequence(history.size() + 1L);
            if (message instanceof ConversationStore.AssistantData assistant) {
                history.add(new AssistantMessage(claim.sessionId(), sequence, Optional.of(claim.messageJobId()), NOW,
                        MessageRole.ASSISTANT, assistant.message()));
            } else if (message instanceof ConversationStore.ToolObservationData observation) {
                history.add(new ToolObservation(claim.sessionId(), sequence, Optional.of(claim.messageJobId()), NOW,
                        MessageRole.TOOL, observation.observationId(), observation.toolName(), observation.input(),
                        observation.output()));
            } else {
                throw new AssertionError("Unexpected nonterminal message: " + message.getClass().getSimpleName());
            }
        }
    }

    private static MessageWorkClaim claim() {
        return new MessageWorkClaim(new MessageJobId("job"), new SessionId("session"), "worker", 1L, NOW, NOW.plusSeconds(30));
    }

    private static UserMessage user() {
        return new UserMessage(new SessionId("session"), new SessionSequence(1L), Optional.of(new MessageJobId("job")), NOW,
                MessageRole.USER, "alice", "hello");
    }

    private record ToolInput(String value) {
    }
}
