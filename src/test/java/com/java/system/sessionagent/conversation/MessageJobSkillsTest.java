package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.SkillCommand;
import com.java.system.sessionagent.conversation.domain.SkillCommandState;
import com.java.system.sessionagent.conversation.domain.SkillMessage;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.adapter.tool.SkillToolCatalog;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.skill.domain.SkillMetadata;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.skill.port.SkillLibrary;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.java.system.sessionagent.conversation.MessageJobTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageJobSkillsTest {
    @TempDir Path root;

    @Test
    void new_submissions_reread_current_bytes_but_accepted_recovery_reuses_persisted_bytes() throws Exception {
        Path definition = Files.createDirectories(root.resolve("review")).resolve("SKILL.md");
        String original = definition("A");
        Files.writeString(definition, original);
        try (SkillLibrary library = spy(new FileSystemSkillLibrary(new SkillProperties(List.of(root), 65536, 128)))) {
            Harness harness = new Harness(library);
            MessageWorkClaim first = harness.submit("job-1", "session-1", "/skill:review question A");
            assertThat(harness.process(first)).isEqualTo(MessageJobProcessingResult.COMPLETED);
            assertThat(skills(harness.requests.getLast())).extracting(skill -> skill.resource().content()).containsExactly(original);
            Files.writeString(definition, definition("B"));
            // A restarted worker sees ACCEPTED and the committed A event, never a new filesystem read.
            assertThat(harness.process(first)).isEqualTo(MessageJobProcessingResult.COMPLETED);
            assertThat(skills(harness.requests.getLast())).extracting(skill -> skill.resource().content()).containsExactly(original);
            verify(library, times(1)).read("skill://review");
            MessageWorkClaim second = harness.submit("job-new-source", "session-1", "/skill:review question B");
            assertThat(harness.process(second)).isEqualTo(MessageJobProcessingResult.COMPLETED);
            assertThat(skills(harness.requests.getLast())).extracting(skill -> skill.resource().content()).containsExactly(original, definition("B"));
            verify(library, times(2)).read("skill://review");
            verify(harness.store, times(2)).resolveSkillCommand(any(), any(), any());
        }
    }

    @Test
    void resolves_only_working_claim_while_queued_command_remains_invisible() {
        SkillLibrary library = library();
        Harness harness = new Harness(library);
        MessageWorkClaim first = harness.submit("A", "session", "/skill:review working A");
        MessageWorkClaim queued = harness.submit("B", "session", "/skill:review queued B");
        harness.process(first);
        assertThat(harness.requests.getLast().history()).filteredOn(UserMessage.class::isInstance)
                .extracting(message -> ((UserMessage) message).message()).containsExactly("/skill:review working A");
        assertThat(harness.histories.get(first.sessionId()).commands().get(queued.messageJobId()).disposition())
                .isEqualTo(SkillCommandState.Disposition.PENDING);
        verify(harness.store, never()).resolveSkillCommand(eq(queued), any(), any());
        verify(library, times(1)).read(any());
        harness.process(queued);
        assertThat(harness.requests.getLast().history()).filteredOn(UserMessage.class::isInstance)
                .extracting(message -> ((UserMessage) message).message()).containsExactly("/skill:review working A", "/skill:review queued B");
    }

    @Test
    void sessions_do_not_share_loaded_content() {
        Harness harness = new Harness(library());
        MessageWorkClaim first = harness.submit("A", "one", "/skill:review private A");
        MessageWorkClaim second = harness.submit("B", "two", "ordinary B");
        harness.process(first);
        harness.process(second);
        assertThat(skills(harness.requests.getLast())).isEmpty();
        assertThat(harness.requests.getLast().history()).singleElement().isInstanceOfSatisfying(UserMessage.class,
                user -> assertThat(user.message()).isEqualTo("ordinary B"));
    }

    @Test
    void unavailable_malformed_and_disabled_selections_finish_without_model_requests() {
        for (String selection : List.of("missing", "", "review/path", "../review")) {
            Harness harness = new Harness(library());
            MessageWorkClaim claim = harness.submit("job", "session", "/skill:" + selection);
            assertThat(harness.process(claim)).isEqualTo(MessageJobProcessingResult.COMPLETED);
            assertThat(harness.requests).isEmpty();
            verify(harness.store).resolveSkillCommand(eq(claim), any(SkillReadResult.Failure.class), any());
            verify(harness.library, never()).read(any());
        }
        try (SkillLibrary disabled = new FileSystemSkillLibrary(new SkillProperties())) {
            Harness harness = new Harness(disabled);
            assertThat(harness.process(harness.submit("disabled", "session", "/skill:review")))
                    .isEqualTo(MessageJobProcessingResult.COMPLETED);
            assertThat(harness.requests).isEmpty();
        }
    }

    @Test
    void unreadable_selection_is_rejected_and_never_admitted_to_next_response() {
        SkillLibrary library = library();
        when(library.read(any())).thenReturn(new SkillReadResult.Failure("SKILL_UNAVAILABLE"));
        Harness harness = new Harness(library);
        MessageWorkClaim rejected = harness.submit("bad", "session", "/skill:review secret bad command");
        assertThat(harness.process(rejected)).isEqualTo(MessageJobProcessingResult.COMPLETED);
        MessageWorkClaim next = harness.submit("next", "session", "ordinary question");
        harness.process(next);
        assertThat(harness.requests).hasSize(1);
        assertThat(harness.requests.getFirst().history()).singleElement().isInstanceOfSatisfying(UserMessage.class,
                user -> assertThat(user.message()).isEqualTo("ordinary question"));
    }

    @Test
    void cancellation_before_admission_and_after_read_never_resolves_or_calls_model() {
        for (boolean cancelAfterRead : List.of(false, true)) {
            SkillLibrary library = library();
            Harness harness = new Harness(library);
            MessageWorkClaim claim = harness.submit("job", "session", "/skill:review");
            AtomicBoolean cancelled = new AtomicBoolean(!cancelAfterRead);
            when(harness.store.isCancellationRequested(claim)).thenAnswer(invocation -> cancelled.get());
            when(library.read(any())).thenAnswer(invocation -> {
                cancelled.set(true);
                return new SkillReadResult.Success(resource("A"));
            });
            assertThat(harness.process(claim)).isEqualTo(MessageJobProcessingResult.CANCELLED);
            verify(harness.store, never()).resolveSkillCommand(any(), any(), any());
            verify(library, times(cancelAfterRead ? 1 : 0)).read(any());
            assertThat(harness.requests).isEmpty();
        }
    }

    @Test
    void ownership_lost_after_read_does_not_resolve_or_call_model() {
        SkillLibrary library = library();
        Harness harness = new Harness(library);
        MessageWorkClaim claim = harness.submit("job", "session", "/skill:review");
        AtomicBoolean owned = new AtomicBoolean(true);
        when(library.read(any())).thenAnswer(invocation -> {
            owned.set(false);
            return new SkillReadResult.Success(resource("A"));
        });
        assertThat(harness.processor().process(claim, owned::get)).isEqualTo(MessageJobProcessingResult.OWNERSHIP_LOST);
        verify(harness.store, never()).resolveSkillCommand(any(), any(), any());
        assertThat(harness.requests).isEmpty();
    }

    @Test
    void ambiguous_resolution_stops_without_resending_or_scheduling_retry() {
        Harness harness = new Harness(library());
        MessageWorkClaim claim = harness.submit("job", "session", "/skill:review");
        doThrow(ConversationStoreFailure.transientFailure(new IllegalStateException("commit reply lost")))
                .when(harness.store).resolveSkillCommand(any(), any(), any());
        assertThat(harness.process(claim)).isEqualTo(MessageJobProcessingResult.STATE_UNCONFIRMED);
        verify(harness.store, times(1)).resolveSkillCommand(any(), any(), any());
        verify(harness.store, never()).scheduleRetry(any(), any(), any());
        verify(harness.store, never()).append(any(), any(), any());
        assertThat(harness.requests).isEmpty();
    }

    @Test
    void automatic_skill_batch_preserves_order_and_continues_after_bad_read() {
        SkillLibrary library = library();
        when(library.read("skill://missing")).thenReturn(new SkillReadResult.Failure("SKILL_NOT_FOUND"));
        Harness harness = new Harness(library);
        MessageWorkClaim claim = harness.submit("job", "session", "read both");
        ConversationModel model = model(TEST_ROUTE_ID, (request, reservation, usage) -> {
            reservation.reserve();
            if (harness.requests.isEmpty()) {
                harness.requests.add(request);
                return result(new ModelReply.UseTools(Optional.empty(), List.of(
                        new ToolRequest(new ToolCallId("bad"), new ToolName("readSkill"), Map.of("uri", "skill://missing")),
                        new ToolRequest(new ToolCallId("good"), new ToolName("readSkill"), Map.of("uri", "skill://review")))));
            }
            return result(new ModelReply.Text("read"));
        });
        harness.processor(model).process(claim, () -> true);
        org.mockito.InOrder reads = inOrder(library);
        reads.verify(library).read("skill://missing");
        reads.verify(library).read("skill://review");
        ArgumentCaptor<ConversationStore.MessageBatch> batches = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
        verify(harness.store, times(2)).append(eq(claim), batches.capture(), any());
        assertThat(batches.getAllValues().getFirst().messages()).hasSize(3);
        assertThat(batches.getAllValues().getFirst().messages().subList(1, 3))
                .extracting(message -> ((ConversationStore.ToolObservationData) message).toolCallId().value()).containsExactly("bad", "good");
    }

    private static String definition(String body) {
        return "---\nname: review\ndescription: Review instructions\n---\n" + body;
    }

    private static List<SkillMessage> skills(ModelRequest request) {
        return request.history().stream().filter(SkillMessage.class::isInstance).map(SkillMessage.class::cast).toList();
    }

    private static SkillResource resource(String body) {
        return new SkillResource("skill://review", "a".repeat(64), body);
    }

    private static SkillLibrary library() {
        SkillLibrary library = mock(SkillLibrary.class);
        when(library.metadata()).thenReturn(List.of(new SkillMetadata("review", "Review", "skill://review")));
        when(library.read(any())).thenReturn(new SkillReadResult.Success(resource("A")));
        return library;
    }

    private static final class Harness {
        private final SkillLibrary library;
        private final ConversationStore store = mockStore();
        private final Map<SessionId, ConversationHistory> histories = new HashMap<>();
        private final List<ModelRequest> requests = new ArrayList<>();

        private Harness(SkillLibrary library) {
            this.library = library;
            when(store.loadContextHistory(any())).thenAnswer(invocation -> histories.get(invocation.<SessionId>getArgument(0)));
            when(store.reserveModelCall(any(), anyInt(), any())).thenReturn(OptionalInt.of(1));
            when(store.resolveSkillCommand(any(), any(), any())).thenAnswer(invocation -> {
                MessageWorkClaim claim = invocation.getArgument(0);
                SkillReadResult read = invocation.getArgument(1);
                ConversationHistory history = histories.get(claim.sessionId());
                List<SessionMessage> messages = new ArrayList<>(history.messages());
                Map<MessageJobId, SkillCommandState> commands = new HashMap<>(history.commands());
                SessionSequence sequence = new SessionSequence(messages.size() + 1L);
                SkillCommandState pending = commands.get(claim.messageJobId());
                boolean accepted = read instanceof SkillReadResult.Success;
                commands.put(claim.messageJobId(), new SkillCommandState(claim.messageJobId(), pending.command(),
                        accepted ? SkillCommandState.Disposition.ACCEPTED : SkillCommandState.Disposition.REJECTED, Optional.of(sequence)));
                if (read instanceof SkillReadResult.Success success) {
                    messages.add(new SkillMessage(claim.sessionId(), sequence, Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.SKILL, success.resource()));
                } else {
                    messages.add(new com.java.system.sessionagent.conversation.domain.RuntimeMessage(claim.sessionId(), sequence, Optional.of(claim.messageJobId()), Instant.EPOCH,
                            MessageRole.RUNTIME, JobFailure.SKILL_UNAVAILABLE.name(), JobFailure.SKILL_UNAVAILABLE.message()));
                }
                histories.put(claim.sessionId(), new ConversationHistory(messages, commands));
                return accepted ? JobStatus.WORKING : JobStatus.DONE;
            });
        }

        private MessageWorkClaim submit(String id, String session, String text) {
            MessageWorkClaim template = claim();
            MessageWorkClaim claim = new MessageWorkClaim(new MessageJobId(id), new SessionId(session), template.modelSelection(), template.workerId(),
                    template.claimNumber(), template.claimedAt(), template.lockedUntil());
            ConversationHistory prior = histories.getOrDefault(claim.sessionId(), new ConversationHistory(List.of(), Map.of()));
            List<SessionMessage> messages = new ArrayList<>(prior.messages());
            messages.add(new UserMessage(claim.sessionId(), new SessionSequence(messages.size() + 1L), Optional.of(claim.messageJobId()), Instant.EPOCH, MessageRole.USER, "alice", text));
            Map<MessageJobId, SkillCommandState> commands = new HashMap<>(prior.commands());
            SkillCommand.parse(text).ifPresent(command -> commands.put(claim.messageJobId(), new SkillCommandState(claim.messageJobId(), command,
                    SkillCommandState.Disposition.PENDING, Optional.empty())));
            histories.put(claim.sessionId(), new ConversationHistory(messages, commands));
            return claim;
        }

        private MessageJobProcessingResult process(MessageWorkClaim claim) {
            return processor().process(claim, () -> true);
        }
        private JobProcessor processor() {
            return processor(model(TEST_ROUTE_ID, (request, reservation, usage) -> {
                reservation.reserve();
                requests.add(request);
                return result(new ModelReply.Text("answer"));
            }));
        }

        private JobProcessor processor(ConversationModel model) {
            return new JobProcessor(store, TestConfiguredModels.withModel(model), new SkillToolCatalog(library), library, Clock.systemUTC(), 3,
                    new MessageJobRetryPolicy(3, Duration.ofSeconds(1)), new NoOpConversationTelemetry());
        }
    }
}
