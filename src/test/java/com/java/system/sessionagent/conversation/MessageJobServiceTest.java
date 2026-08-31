package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageJobService;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import com.java.system.sessionagent.tool.application.ToolRegistration;
import com.java.system.sessionagent.tool.domain.ToolDefinition;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.domain.ToolResult;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MessageJobServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void appends_first_call_text_and_completes() {
        RecordingStore store = new RecordingStore();
        ScriptedModel model = new ScriptedModel(new ModelReply.Text("Answer"));

        service(store, model, List.of(), 12).process(store.claim, () -> true);

        assertThat(store.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
            assertThat(batch.messages()).containsExactly(new ConversationStore.AssistantData("Answer"));
        });
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void appends_text_and_ordered_observations_then_reloads_history_for_final_text() {
        RecordingStore store = new RecordingStore();
        List<String> executionOrder = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(
                new ModelReply.UseTools(Optional.of("Inspecting."), List.of(
                        new ToolRequest(new ToolName("first"), "{}"),
                        new ToolRequest(new ToolName("second"), "{}"))),
                new ModelReply.Text("Done"));

        service(store, model, List.of(registration("first", executionOrder), registration("second", executionOrder)), 12)
                .process(store.claim, () -> true);

        assertThat(store.batches()).satisfiesExactly(
                batch -> {
                    assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.KEEP_WORKING);
                    assertThat(batch.messages()).extracting(Object::getClass)
                            .containsExactly(
                                    ConversationStore.AssistantData.class,
                                    ConversationStore.ToolObservationData.class,
                                    ConversationStore.ToolObservationData.class);
                },
                batch -> assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE));
        assertThat(executionOrder).containsExactly("first", "second");
        assertThat(model.historySizes).containsExactly(0, 3);
    }

    @Test
    void continues_after_a_tool_failure_and_persists_every_observation_in_order() {
        RecordingStore store = new RecordingStore();
        List<String> executionOrder = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(new ModelReply.UseTools(Optional.empty(), List.of(
                new ToolRequest(new ToolName("broken"), "{}"), new ToolRequest(new ToolName("second"), "{}"))),
                new ModelReply.Text("Done"));
        ToolRegistration<Object> broken = new ToolRegistration<>(definition("broken"), Object.class, ignored -> {
            executionOrder.add("broken");
            throw com.java.system.sessionagent.tool.application.ToolExecutionFailure.invalidInput();
        });

        service(store, model, List.of(broken, registration("second", executionOrder)), 12).process(store.claim, () -> true);

        assertThat(executionOrder).containsExactly("broken", "second");
        assertThat(observations(store.batches().getFirst())).extracting(ConversationStore.ToolObservationData::toolName)
                .containsExactly("broken", "second");
        assertThat(observations(store.batches().getFirst()).getFirst().output()).contains("TOOL_INPUT_INVALID");
    }

    @Test
    void completes_with_limit_without_executing_tools_when_final_call_requests_tools() {
        RecordingStore store = new RecordingStore();
        store.nextOrdinal = 12;
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(new ModelReply.UseTools(Optional.of("Partial"),
                List.of(new ToolRequest(new ToolName("tool"), "{}"))));

        service(store, model, List.of(registration("tool", executions)), 12).process(store.claim, () -> true);

        assertThat(executions).hasValue(0);
        assertThat(store.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
            assertThat(batch.messages()).containsExactly(
                    new ConversationStore.AssistantData("Partial"),
                    new ConversationStore.RuntimeData("MODEL_CALL_LIMIT_REACHED", "Runtime model call limit reached."));
        });
    }

    @Test
    void schedules_transient_failure_without_history_but_completes_when_retries_are_exhausted() {
        RecordingStore retryStore = new RecordingStore();
        retryStore.job = Optional.of(retryStore.job(0, 1));
        service(retryStore, new ScriptedModel(ModelCallFailure.transientFailure()), List.of(), 12)
                .process(retryStore.claim, () -> true);
        assertThat(retryStore.batches()).isEmpty();
        assertThat(retryStore.retryScheduled).isTrue();

        RecordingStore exhaustedStore = new RecordingStore();
        exhaustedStore.job = Optional.of(exhaustedStore.job(3, 1));
        service(exhaustedStore, new ScriptedModel(ModelCallFailure.transientFailure()), List.of(), 12)
                .process(exhaustedStore.claim, () -> true);
        assertThat(exhaustedStore.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
            assertThat(batch.messages()).containsExactly(
                    new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime model is unavailable."));
        });
    }

    @Test
    void denied_reservation_never_calls_model_or_tools_and_stale_limit_append_fabricates_no_history() {
        RecordingStore store = new RecordingStore();
        store.reservationsAllowed = false;
        store.rejectAppend = true;
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel(new ModelReply.UseTools(Optional.empty(),
                List.of(new ToolRequest(new ToolName("tool"), "{}"))));

        service(store, model, List.of(registration("tool", executions)), 12).process(store.claim, () -> true);

        assertThat(model.calls).isZero();
        assertThat(executions).hasValue(0);
        assertThat(store.batches()).isEmpty();
        assertThat(store.appendAttempts).isEqualTo(1);
    }

    @Test
    void records_correctable_and_context_failures_as_distinct_runtime_messages() {
        RecordingStore correctableStore = new RecordingStore();
        service(correctableStore, new ScriptedModel(ModelCallFailure.correctable(), new ModelReply.Text("Done")), List.of(), 2)
                .process(correctableStore.claim, () -> true);
        assertThat(correctableStore.batches()).extracting(batch -> batch.messages().getFirst())
                .containsExactly(new ConversationStore.RuntimeData("MODEL_OUTPUT_INVALID", "Runtime model output is invalid."),
                        new ConversationStore.AssistantData("Done"));

        RecordingStore contextStore = new RecordingStore();
        service(contextStore, new ScriptedModel(ModelCallFailure.contextTooLarge()), List.of(), 12)
                .process(contextStore.claim, () -> true);
        assertThat(contextStore.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.jobUpdate()).isEqualTo(ConversationStore.JobUpdate.COMPLETE);
            assertThat(batch.messages()).containsExactly(
                    new ConversationStore.RuntimeData("CONTEXT_TOO_LARGE", "Runtime model context is too large."));
        });
    }

    private static MessageJobService service(RecordingStore store, ConversationModel model,
                                             List<ToolRegistration<?>> registrations, int maxModelCalls) {
        return new MessageJobService(store, model, new DirectToolRegistry(registrations), CLOCK, maxModelCalls,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    private static ToolRegistration<Object> registration(String name, List<String> executionOrder) {
        return new ToolRegistration<>(definition(name), Object.class, ignored -> {
            executionOrder.add(name);
            return new ToolResult(Optional.empty(), Optional.empty(), "{\"tool\":\"" + name + "\"}");
        });
    }

    private static ToolRegistration<Object> registration(String name, AtomicInteger executions) {
        return new ToolRegistration<>(definition(name), Object.class, ignored -> {
            executions.incrementAndGet();
            return new ToolResult(Optional.empty(), Optional.empty(), "{}");
        });
    }

    private static ToolDefinition definition(String name) {
        return new ToolDefinition(new ToolName(name), "v1", name, "{\"type\":\"object\"}", ToolKind.CATALOG);
    }

    private static List<ConversationStore.ToolObservationData> observations(ConversationStore.MessageBatch batch) {
        return batch.messages().stream().map(ConversationStore.ToolObservationData.class::cast).toList();
    }

    private static final class ScriptedModel implements ConversationModel {
        private final List<Object> replies;
        private final List<Integer> historySizes = new ArrayList<>();
        private int index;
        private int calls;

        private ScriptedModel(Object... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public ModelReply respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usageObserver) {
            historySizes.add(request.history().size());
            reservation.reserve();
            calls++;
            Object next = replies.get(index++);
            if (next instanceof ModelCallFailure failure) {
                throw failure;
            }
            return (ModelReply) next;
        }
    }

    private static final class RecordingStore implements ConversationStore {
        private final MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId("job-1"), new SessionId("session-1"), "worker", 1,
                CLOCK.instant(), CLOCK.instant().plusSeconds(60));
        private final List<ConversationStore.MessageBatch> messageBatches = new ArrayList<>();
        private Optional<MessageJobProjection> job = Optional.empty();
        private int nextOrdinal = 1;
        private boolean reservationsAllowed = true;
        private boolean rejectAppend;
        private boolean retryScheduled;
        private int appendAttempts;

        @Override public List<SessionMessage> loadHistory(SessionId sessionId) { return List.of(); }
        @Override public List<SessionMessage> loadHistory(SessionId sessionId, MessageJobId messageJobId) { return history(); }
        @Override public OptionalInt reserveModelCall(MessageWorkClaim ignored, Instant now) { return OptionalInt.of(nextOrdinal++); }
        @Override public OptionalInt reserveModelCall(MessageWorkClaim ignored, int maxModelCalls, Instant now) {
            if (!reservationsAllowed || nextOrdinal > maxModelCalls) { return OptionalInt.empty(); }
            return OptionalInt.of(nextOrdinal++);
        }
        @Override public void append(MessageWorkClaim ignored, MessageBatch batch, Instant now) {
            appendAttempts++;
            if (rejectAppend) { throw new com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException(); }
            messageBatches.add(batch);
        }
        @Override public boolean scheduleRetry(MessageWorkClaim ignored, Duration retryDelay) { retryScheduled = true; return true; }
        @Override public Optional<MessageJobProjection> readJob(MessageJobId messageJobId) { return job; }
        @Override public com.java.system.sessionagent.conversation.domain.MessageReceipt receive(com.java.system.sessionagent.conversation.domain.IncomingMessage incomingMessage) { throw new UnsupportedOperationException(); }
        @Override public Optional<MessageWorkClaim> claimNext(String workerId, Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public boolean extendClaim(MessageWorkClaim claim, Duration leaseDuration) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.ToolMessage appendTool(MessageWorkClaim claim, com.java.system.sessionagent.conversation.domain.ResultId resultId, String modelCallId, String modelContext, ToolData toolData, Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.FeedbackMessage appendFeedback(MessageWorkClaim claim, String code, String message, boolean terminal, Optional<String> modelCallId, Optional<String> toolName, Optional<String> rejectedArguments, Optional<String> modelContext, Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public com.java.system.sessionagent.conversation.domain.AssistantMessage appendAssistant(MessageWorkClaim claim, String message, Instant createdAt) { throw new UnsupportedOperationException(); }
        @Override public Optional<ResultProjection> readResult(com.java.system.sessionagent.conversation.domain.ResultId resultId) { return Optional.empty(); }

        private List<ConversationStore.MessageBatch> batches() { return List.copyOf(messageBatches); }
        private List<SessionMessage> history() {
            List<SessionMessage> history = new ArrayList<>();
            long sequence = 1;
            for (ConversationStore.MessageBatch batch : messageBatches) {
                for (ConversationStore.MessageData message : batch.messages()) {
                    if (message instanceof ConversationStore.AssistantData assistant) {
                        history.add(new com.java.system.sessionagent.conversation.domain.AssistantMessage(claim.sessionId(),
                                new com.java.system.sessionagent.conversation.domain.SessionSequence(sequence++), Optional.of(claim.messageJobId()),
                                CLOCK.instant(), com.java.system.sessionagent.conversation.domain.MessageRole.ASSISTANT, assistant.message()));
                    }
                    if (message instanceof ConversationStore.ToolObservationData observation) {
                        history.add(new com.java.system.sessionagent.conversation.domain.ToolObservation(claim.sessionId(),
                                new com.java.system.sessionagent.conversation.domain.SessionSequence(sequence++), Optional.of(claim.messageJobId()),
                                CLOCK.instant(), com.java.system.sessionagent.conversation.domain.MessageRole.TOOL, observation.observationId(),
                                observation.toolName(), observation.input(), observation.output()));
                    }
                }
            }
            return List.copyOf(history);
        }
        private MessageJobProjection job(int retryCount, int modelCalls) {
            return new MessageJobProjection(claim.messageJobId(), claim.sessionId(),
                    com.java.system.sessionagent.conversation.domain.JobStatus.WORKING, retryCount, modelCalls, Optional.empty());
        }
    }
}
