package com.java.system.sessionagent.storage.adapter.postgres;

import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.domain.IntentRequest;
import com.java.system.sessionagent.conversation.domain.IntentResult;

import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.conversation.domain.WaitingChoice;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import java.util.concurrent.atomic.AtomicInteger;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.JobCancellation;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobPhase;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.ContextUsageProjection;
import com.java.system.sessionagent.conversation.domain.ContextHistoryView;
import com.java.system.sessionagent.conversation.domain.ContextEstimate;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.artifact.port.ArtifactStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.model.adapter.springai.HistoryProjector;
import com.java.system.sessionagent.model.adapter.springai.InvalidConversationHistoryException;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.SkillReadReceipt;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolInvocation;
import com.java.system.sessionagent.tool.port.ToolExecutionContext;
import com.java.system.sessionagent.tool.port.ToolExecutionAuthority;
import com.java.system.sessionagent.skill.domain.SkillResource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConversationCommitPostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:01Z");
    private static final ModelSelection TEST_SELECTION = new ModelSelection("test", "v1:test");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void migrateFreshSchema() {
        POSTGRES.start();
        flyway().clean();
        flyway().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void cleanSchema() {
        flyway().clean();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pending_tool_storage_recovery_is_durable_and_visible_to_the_reclaimed_model_attempt(boolean responseLost) {
        ConversationStore actual = store();
        MessageReceipt receipt = receive(actual, new IncomingMessage("pending-recovery", "alice", "source", "hello"));
        MessageWorkClaim claim = actual.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        ConversationStore interrupted = org.mockito.Mockito.spy(actual);
        AtomicInteger admissions = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            JobProgress progress = invocation.getArgument(1);
            if (progress.phase() == JobPhase.TOOL_EXECUTION && admissions.incrementAndGet() == 2) {
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("admission failed once"));
            }
            return invocation.callRealMethod();
        }).when(interrupted).updateProgress(org.mockito.ArgumentMatchers.eq(claim), org.mockito.ArgumentMatchers.any());
        if (responseLost) {
            org.mockito.Mockito.doAnswer(invocation -> {
                invocation.callRealMethod();
                throw ConversationStoreFailure.transientFailure(new IllegalStateException("atomic recovery response lost"));
            }).when(interrupted).appendAndScheduleRetry(org.mockito.ArgumentMatchers.eq(claim),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("test"), "opaque-v1", new byte[] {1, 2});
        ToolOutput output = new ToolOutput(false, Map.of("actual", "completed"));
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        ToolCatalog tools = () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()),
                        (context, arguments) -> { firstCalls.incrementAndGet(); return output; }, ToolCapability.ORDINARY),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()),
                        (context, arguments) -> { secondCalls.incrementAndGet(); return output; }, ToolCapability.ORDINARY)));
        List<ModelRequest> requests = new ArrayList<>();
        ConversationModel model = new ConversationModel() {
            @Override public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
                throw new AssertionError("Unexpected intent request");
            }
            @Override public ModelRouteId routeId() { return new ModelRouteId("test"); }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observer) {
                reservation.reserve();
                requests.add(request);
                return requests.size() == 1
                        ? new ModelCallResult(new ModelReply.UseTools(Optional.of("Checking"), List.of(
                                new ToolRequest(new ToolCallId("one"), new ToolName("first"), Map.of()),
                                new ToolRequest(new ToolCallId("two"), new ToolName("second"), Map.of()))), Optional.of(continuation))
                        : new ModelCallResult(new ModelReply.Text("Recovered"), Optional.empty());
            }
        };

        assertThat(recoveryProcessor(interrupted, model, tools).process(claim, () -> true))
                .isEqualTo(responseLost ? MessageJobProcessingResult.STATE_UNCONFIRMED : MessageJobProcessingResult.RETRY_SCHEDULED);

        ConversationStore restarted = store();
        assertThat(restarted.readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.RETRY);
            assertThat(job.retryCount()).isOne();
            assertThat(job.modelCallCount()).isOne();
            assertThat(job.failure()).contains(JobFailure.STORAGE_UNAVAILABLE);
        });
        List<SessionMessage> history = restarted.loadHistory(receipt.sessionId());
        assertThat(history).extracting(SessionMessage::role).containsExactly(
                MessageRole.USER, MessageRole.ASSISTANT_TOOL_CALLS, MessageRole.TOOL, MessageRole.TOOL);
        assertThat(((ToolObservation) history.get(2)).output()).isEqualTo(output.asStructuredValue());
        assertThat(((ToolObservation) history.get(3)).output()).isEqualTo(ToolOutput.runtimeFailure(
                "TOOL_NOT_EXECUTED", "Tool was not executed because Runtime storage failed.").asStructuredValue());
        MessageWorkClaim retry = restarted.claimNext("recovery", Duration.ofMinutes(1)).orElseThrow();
        assertThat(retry.claimNumber()).isGreaterThan(claim.claimNumber());
        assertThat(restarted.loadContinuations(retry)).containsEntry(new SessionSequence(2), continuation);
        assertThat(recoveryProcessor(restarted, model, tools).process(retry, () -> true))
                .isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().history()).containsExactlyElementsOf(history);
        assertThat(requests.getLast().continuations()).containsEntry(new SessionSequence(2), continuation);
        assertThat(firstCalls).hasValue(1);
        assertThat(secondCalls).hasValue(0);
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow().modelCallCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retry_transition_failure_rolls_back_history_continuation_checkpoint_and_receipts(boolean rejectFence) {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("retry-rollback", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticSkillOutput(store, claim);
        ConversationStore.MessageBatch batch = recoveryReceiptBatch(claim, output);
        jdbcTemplate.execute("""
                create function reject_recovery_retry() returns trigger language plpgsql as $$
                begin
                    if new.status='RETRY' then
                """ + (rejectFence ? "return null;" : "raise exception 'injected retry persistence failure';") + """
                    end if;
                    return new;
                end; $$
                """);
        jdbcTemplate.execute("create trigger reject_recovery_retry before update on message_job for each row execute function reject_recovery_retry()");

        assertThatThrownBy(() -> store.appendAndScheduleRetry(claim, batch, Duration.ZERO, JobFailure.STORAGE_UNAVAILABLE, NOW))
                .isInstanceOf(rejectFence ? StaleWorkClaimException.class : ConversationStoreFailure.class);

        ConversationStore restarted = store();
        assertThat(restarted.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(restarted.loadContinuations(claim)).isEmpty();
        assertThat(restarted.loadUsageCheckpoint(claim.sessionId(), TEST_SELECTION,
                new ModelDescriptor(new ModelRouteId("test"), "fake", 32_000), "c".repeat(64), 0)).isEmpty();
        assertThat(restarted.readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.WORKING);
            assertThat(job.retryCount()).isZero();
        });
        assertThat(jdbcTemplate.queryForObject("select status from skill_load_attempt", String.class)).isEqualTo("STARTED");
        assertThat(jdbcTemplate.queryForObject("select history_sequence from skill_load_attempt", Long.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select observation_sequence from generated_artifact", Long.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("select released from generated_artifact", Boolean.class)).isFalse();
        jdbcTemplate.execute("drop trigger reject_recovery_retry on message_job");
        assertThat(restarted.appendAndScheduleRetry(claim, batch, Duration.ZERO, JobFailure.STORAGE_UNAVAILABLE, NOW))
                .isEqualTo(JobStatus.RETRY);
        assertThat(store().loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(jdbcTemplate.queryForObject("select status from skill_load_attempt", String.class)).isEqualTo("COMMITTED_SUCCESS");
        assertThat(jdbcTemplate.queryForObject("select history_sequence from skill_load_attempt", Long.class)).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject("select observation_sequence from generated_artifact", Long.class)).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject("select released from generated_artifact", Boolean.class)).isFalse();
        assertThat(store().loadUsageCheckpoint(claim.sessionId(), TEST_SELECTION,
                new ModelDescriptor(new ModelRouteId("test"), "fake", 32_000), "c".repeat(64), 0))
                .hasValueSatisfying(checkpoint -> assertThat(checkpoint.responseBoundary().value()).isEqualTo(2L));
    }

    @Test
    void stale_recovery_claim_cannot_append_or_schedule_retry() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("stale-recovery", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until=clock_timestamp()-interval '1 second'");
        MessageWorkClaim replacement = store().claimNext("replacement", Duration.ofMinutes(1)).orElseThrow();
        assertThatThrownBy(() -> store.appendAndScheduleRetry(claim, recoveryBatch(skillBatch(
                new ToolOutput(false, Map.of())).messages()), Duration.ZERO, JobFailure.STORAGE_UNAVAILABLE, NOW))
                .isInstanceOf(StaleWorkClaimException.class);
        assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(store().loadContinuations(replacement)).isEmpty();
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow().retryCount()).isZero();
    }

    @Test
    void cancellation_wins_atomic_recovery_retaining_receipts_but_not_continuation_or_retry() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("cancel-recovery", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticSkillOutput(store, claim);
        ConversationStore.MessageBatch batch = recoveryReceiptBatch(claim, output);
        store.requestCancellation(receipt.messageJobId());
        assertThat(store.appendAndScheduleRetry(claim, batch,
                Duration.ZERO, JobFailure.STORAGE_UNAVAILABLE, NOW)).isEqualTo(JobStatus.CANCELLED);
        ConversationStore restarted = store();
        assertThat(restarted.readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(job.retryCount()).isZero();
            assertThat(job.failure()).isEmpty();
        });
        List<SessionMessage> history = restarted.loadHistory(receipt.sessionId());
        assertThat(history).extracting(SessionMessage::role).containsExactly(MessageRole.USER,
                MessageRole.ASSISTANT_TOOL_CALLS, MessageRole.TOOL, MessageRole.TOOL, MessageRole.RUNTIME);
        assertThat(((RuntimeMessage) history.getLast()).code()).isEqualTo(JobCancellation.CODE);
        assertThat(jdbcTemplate.queryForObject("select count(*) from model_continuation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select status from skill_load_attempt", String.class)).isEqualTo("COMMITTED_SUCCESS");
        assertThat(jdbcTemplate.queryForObject("select observation_sequence from generated_artifact", Long.class)).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject("select released from generated_artifact", Boolean.class)).isFalse();
        assertThat(restarted.claimNext("recovery", Duration.ofMinutes(1))).isEmpty();
    }

    private ConversationStore.MessageBatch recoveryReceiptBatch(MessageWorkClaim claim, ToolOutput skill) {
        Map<String, Object> arguments = Map.of("title", "Report", "content", "x");
        ToolInvocation invocation = new ToolInvocation(new ToolExecutionContext(UUID.fromString(claim.sessionId().value()),
                UUID.fromString(claim.messageJobId().value()), 1, 1),
                new ToolExecutionAuthority(claim.workerId(), claim.claimNumber()));
        ArtifactStore.Saved saved = new PostgresArtifactStore(dataSource(), new ObjectMapper()).create(invocation,
                "Report", "md", "text/markdown", new byte[] {'x'}, "createMarkdownReport", arguments, Map.of());
        ToolOutput artifact = new ToolOutput(false, saved.result(), Optional.of(saved.receipt()));
        return recoveryBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("skill"), "readSkill", Map.of("uri", "skill://review")),
                        new ConversationStore.ToolCallData(new ToolCallId("report"), "createMarkdownReport", arguments))),
                new ConversationStore.ToolObservationData(new ToolCallId("skill"), "readSkill", skill.asStructuredValue(),
                        Optional.empty(), skill.skillReadReceipt()),
                new ConversationStore.ToolObservationData(new ToolCallId("report"), "createMarkdownReport",
                        artifact.asStructuredValue(), artifact.artifactReceipt())));
    }

    private static ConversationStore.MessageBatch recoveryBatch(List<ConversationStore.MessageData> messages) {
        return new ConversationStore.MessageBatch(messages, ConversationStore.JobUpdate.KEEP_WORKING,
                Optional.of(new ModelContinuation(new ModelRouteId("test"), "opaque-v1", new byte[] {1, 2})),
                Optional.of(new ConversationStore.UsageCheckpointData(TEST_SELECTION,
                        new ModelDescriptor(new ModelRouteId("test"), "fake", 32_000), 1, 20, 10, 30,
                        "c".repeat(64), 0, new SessionSequence(1))),
                Optional.empty(), ConversationStore.CompletionDisposition.NONE);
    }

    private static JobProcessor recoveryProcessor(ConversationStore store, ConversationModel model, ToolCatalog tools) {
        return new JobProcessor(store, TestConfiguredModels.withModel(model), tools,
                new FileSystemSkillLibrary(new SkillProperties()), Clock.systemUTC(), 3,
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, Duration.ofNanos(1)),
                new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());
    }

    @Test
    void waiting_history_and_generation_roll_back_together_when_wait_persistence_fails() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("rollback-wait", "alice", "origin", "investigate"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        store.enterWrapUp(claim, NOW);
        jdbcTemplate.execute("""
                create function reject_waiting_choice() returns trigger language plpgsql as $$
                begin raise exception 'injected waiting write failure'; end; $$
                """);
        jdbcTemplate.execute("create trigger reject_waiting_choice before insert on session_waiting_choice for each row execute function reject_waiting_choice()");
        assertThatThrownBy(() -> store.append(claim, waitingBatch("Continue or organize?"), NOW)).isInstanceOf(RuntimeException.class);
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(store.readJob(receipt.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.WORKING);
        assertThat(jdbcTemplate.queryForObject("select count(*) from session_waiting_choice", Integer.class)).isZero();
    }

    @Test
    void clarification_preserves_source_generation_and_the_persisted_decision_cannot_be_reinterpreted() {
        ConversationStore store = store();
        receive(store, new IncomingMessage("clarification", "alice", "origin", "investigate"));
        MessageWorkClaim origin = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        store.enterWrapUp(origin, NOW);
        store.append(origin, waitingBatch("Continue or organize?"), NOW);
        receive(store, new IncomingMessage("clarification", "alice", "ambiguous", "yes"));
        MessageWorkClaim ambiguous = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        WaitingChoice original = store.loadJobControl(ambiguous).waiting().orElseThrow();
        store.reserveModelCall(ambiguous, ModelCallPurpose.INTENT, 2, NOW);
        store.resolveFollowUp(ambiguous, original, FollowUpIntent.CLARIFY, NOW);
        assertThat(store.resolveFollowUp(ambiguous, original, FollowUpIntent.DELIVER, NOW).waiting()).contains(original);
        store.append(ambiguous, waitingBatch("Which option did you mean?"), NOW);
        receive(store, new IncomingMessage("clarification", "alice", "choice", "continue investigating"));
        MessageWorkClaim next = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        assertThat(store.loadJobControl(next).waiting()).contains(original);
        assertThat(store.loadHistory(next.sessionId()).stream().filter(AssistantMessage.class::isInstance)).hasSize(2);
    }

    @Test
    void a_lost_waiting_append_response_leaves_a_recoverable_committed_completion_without_requery() {
        ConversationStore actual = store();
        ConversationStore interrupted = org.mockito.Mockito.spy(actual);
        MessageReceipt receipt = receive(actual, new IncomingMessage("lost-wait-reply", "alice", "origin", "investigate"));
        MessageWorkClaim claim = actual.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw ConversationStoreFailure.transientFailure(new IllegalStateException("append response lost after commit"));
        }).when(interrupted).append(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        AtomicInteger requests = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
                throw new AssertionError("Unexpected intent request in ordinary-job fixture");
            }
            @Override public ModelRouteId routeId() { return new ModelRouteId("test"); }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observer) {
                reservation.reserve();
                requests.incrementAndGet();
                return new ModelCallResult(request.purpose() == ModelRequest.Purpose.WRAP_UP
                        ? new ModelReply.Text("Continue or organize?")
                        : new ModelReply.UseTools(Optional.empty(), List.of(new ToolRequest(new ToolCallId("discarded"),
                                new ToolName("opaque"), Map.of()))), Optional.empty());
            }
        };
        JobProcessor processor = new JobProcessor(interrupted, TestConfiguredModels.withModel(model), () -> new ToolSnapshot(List.of()),
                new FileSystemSkillLibrary(new SkillProperties()), Clock.systemUTC(), 1,
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, Duration.ofSeconds(60)),
                new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());
        assertThat(processor.process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.STATE_UNCONFIRMED);
        assertThat(actual.readJob(receipt.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
        assertThat(actual.loadHistory(receipt.sessionId())).extracting(SessionMessage::role).containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(jdbcTemplate.queryForObject("select pending from session_waiting_choice", Boolean.class)).isTrue();
        assertThat(actual.claimNext("recovery", Duration.ofMinutes(1))).isEmpty();
        assertThat(requests).hasValue(2);
    }

    private static ConversationStore.MessageBatch waitingBatch(String message) {
        return new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData(message)), ConversationStore.JobUpdate.COMPLETE,
                Optional.empty(), Optional.empty(), Optional.empty(), ConversationStore.CompletionDisposition.WAITING);
    }

    @Test
    void retains_message_role_model_budget_and_compaction_reason_constraints() {
        String roleChecks = jdbcTemplate.queryForObject("""
                select string_agg(pg_get_constraintdef(constraint_record.oid), ' ')
                from pg_constraint constraint_record join pg_class relation on relation.oid = constraint_record.conrelid
                where relation.relname = 'session_message' and constraint_record.contype = 'c'
                """, String.class);
        String jobChecks = jdbcTemplate.queryForObject("""
                select string_agg(pg_get_constraintdef(constraint_record.oid), ' ')
                from pg_constraint constraint_record join pg_class relation on relation.oid = constraint_record.conrelid
                where relation.relname = 'message_job' and constraint_record.contype = 'c'
                """, String.class);
        String compactionChecks = jdbcTemplate.queryForObject("""
                select string_agg(pg_get_constraintdef(constraint_record.oid), ' ')
                from pg_constraint constraint_record join pg_class relation on relation.oid = constraint_record.conrelid
                where relation.relname = 'session_compaction' and constraint_record.contype = 'c'
                """, String.class);

        assertThat(roleChecks).contains("USER", "TOOL", "ASSISTANT", "ASSISTANT_TOOL_CALLS", "RUNTIME").doesNotContain("FEEDBACK");
        assertThat(jobChecks).contains("model_calls >= 0").doesNotContain("between 0 and 12");
        assertThat(compactionChecks).contains("THRESHOLD", "OVERFLOW").doesNotContain("PROACTIVE");
    }

    @Test
    void cancellation_retains_complete_tool_batches_and_their_usage_boundary_but_discards_continuations() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("cancel-thread", "alice", "batch", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("test"), "opaque", new byte[] {1});
        store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("prior"), "first", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("prior"), "first", Map.of("result", "prior"))),
                ConversationStore.JobUpdate.KEEP_WORKING, Optional.of(continuation), ConversationStore.CompletionDisposition.NONE), NOW);
        store.requestCancellation(receipt.messageJobId());
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("test"), "fake", 32_000);
        String shape = "b".repeat(64);

        assertThat(store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.of("batch text"), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("current"), "second", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("current"), "second", Map.of("result", "actual")),
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "superseded failure")),
                ConversationStore.JobUpdate.KEEP_WORKING, Optional.of(continuation), Optional.of(
                        new ConversationStore.UsageCheckpointData(TEST_SELECTION, descriptor, 2, 20, 10, 30, shape, 0,
                                new SessionSequence(3))),
                Optional.of(JobFailure.MODEL_UNAVAILABLE), ConversationStore.CompletionDisposition.NONE), NOW))
                .isEqualTo(JobStatus.CANCELLED);

        List<SessionMessage> history = store().loadHistory(receipt.sessionId());
        assertThat(history).extracting(SessionMessage::role).containsExactly(MessageRole.USER,
                MessageRole.ASSISTANT_TOOL_CALLS, MessageRole.TOOL, MessageRole.ASSISTANT_TOOL_CALLS,
                MessageRole.TOOL, MessageRole.RUNTIME);
        assertThat(((RuntimeMessage) history.getLast()).code()).isEqualTo(JobCancellation.CODE);
        assertThat(store.loadUsageCheckpoint(receipt.sessionId(), TEST_SELECTION, descriptor, shape, 0))
                .hasValueSatisfying(checkpoint -> assertThat(checkpoint.responseBoundary()).isEqualTo(history.get(3).sequence()));
        assertThat(jdbcTemplate.queryForObject("select count(*) from model_continuation", Integer.class)).isZero();
        assertThat(store.readJob(receipt.messageJobId()).orElseThrow().failure()).isEmpty();
    }

    @Test
    void atomically_appends_native_assistant_calls_and_tool_results() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(java.util.Optional.of("I will inspect both."), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "mcp_lookup", java.util.Map.of("query", Map.of("terms", List.of("fees", "late")))),
                        new ConversationStore.ToolCallData(new ToolCallId("call-2"), "mcp_entries", java.util.Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "mcp_lookup", java.util.Map.of("isError", false, "result", java.util.Map.of("hits", List.of(Map.of("id", 7))))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-2"), "mcp_entries", java.util.Map.of("isError", true, "result", Map.of("failure", Map.of("code", "TOOL_TIMEOUT"))))),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.NONE), NOW);

        ConversationStore restartedStore = store();
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> history = restartedStore.loadHistory(receipt.sessionId());
        assertThat(history).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(history.get(1)).isInstanceOfSatisfying(AssistantToolCallsMessage.class, calls -> {
            assertThat(calls.message()).contains("I will inspect both.");
            assertThat(calls.requests()).extracting(request -> request.toolCallId().value()).containsExactly("call-1", "call-2");
            assertThat(calls.requests()).extracting(request -> request.toolName().value()).containsExactly("mcp_lookup", "mcp_entries");
            assertThat(calls.requests().getFirst().arguments()).isEqualTo(Map.of("query", Map.of("terms", List.of("fees", "late"))));
        });
        assertThat(history.subList(2, 4)).allSatisfy(message -> assertThat(message).isInstanceOf(ToolObservation.class));
        assertThat(history.subList(2, 4)).extracting(message -> ((ToolObservation) message).toolCallId().value())
                .containsExactly("call-1", "call-2");
        assertThat(history.subList(2, 4)).extracting(message -> ((ToolObservation) message).toolName())
                .containsExactly("mcp_lookup", "mcp_entries");
        assertThat(((ToolObservation) history.get(2)).output()).isEqualTo(Map.of("isError", false, "result", Map.of("hits", List.of(Map.of("id", 7)))));
        assertThat(((ToolObservation) history.get(3)).output()).isEqualTo(Map.of("isError", true, "result", Map.of("failure", Map.of("code", "TOOL_TIMEOUT"))));
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class,
                java.util.UUID.fromString(receipt.messageJobId().value()))).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("select count(*) from tool_observation", Integer.class)).isEqualTo(2);
        assertThat(restartedStore.claimNext("recovery-worker", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void loads_only_the_next_database_history_page_in_sequence_order_and_preserves_raw_message_variants() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "paged-source", "original user message"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantData("assistant text"),
                new ConversationStore.AssistantToolCallsData(Optional.of("tool preface"), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-page"), "mcp_lookup",
                                Map.of("query", Map.of("term", "history"))))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-page"), "mcp_lookup",
                        Map.of("result", List.of(Map.of("id", 7)))),
                new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "Runtime model is unavailable.")),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.NONE), NOW);
        insertMalformedAssistantCalls(receipt, claim, 6, "[1]");

        Optional<List<SessionMessage>> page = store().loadHistoryPage(receipt.sessionId(), 0, 5);

        assertThat(page).hasValueSatisfying(history -> {
            assertThat(history).extracting(message -> message.sequence().value()).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(history.get(0)).isInstanceOfSatisfying(UserMessage.class, user -> {
                assertThat(user.role()).isEqualTo(MessageRole.USER);
                assertThat(user.message()).isEqualTo("original user message");
            });
            assertThat(history.get(1)).isInstanceOfSatisfying(AssistantMessage.class, assistant -> {
                assertThat(assistant.role()).isEqualTo(MessageRole.ASSISTANT);
                assertThat(assistant.message()).isEqualTo("assistant text");
            });
            assertThat(history.get(2)).isInstanceOfSatisfying(AssistantToolCallsMessage.class, calls -> {
                assertThat(calls.role()).isEqualTo(MessageRole.ASSISTANT_TOOL_CALLS);
                assertThat(calls.message()).contains("tool preface");
                assertThat(calls.requests()).extracting(request -> request.toolCallId().value()).containsExactly("call-page");
                assertThat(calls.requests().getFirst().arguments()).isEqualTo(Map.of("query", Map.of("term", "history")));
            });
            assertThat(history.get(3)).isInstanceOfSatisfying(ToolObservation.class, observation -> {
                assertThat(observation.role()).isEqualTo(MessageRole.TOOL);
                assertThat(observation.toolName()).isEqualTo("mcp_lookup");
                assertThat(observation.output()).isEqualTo(Map.of("result", List.of(Map.of("id", 7))));
            });
            assertThat(history.get(4)).isInstanceOfSatisfying(RuntimeMessage.class, runtime -> {
                assertThat(runtime.role()).isEqualTo(MessageRole.RUNTIME);
                assertThat(runtime.code()).isEqualTo("MODEL_UNAVAILABLE");
                assertThat(runtime.message()).isEqualTo("Runtime model is unavailable.");
            });
        });
        assertThat(store().loadHistoryPage(receipt.sessionId(), 6, 1)).contains(List.of());
        assertThat(store().loadHistoryPage(new com.java.system.sessionagent.conversation.domain.SessionId(
                "a1d4cefe-d5b5-4f40-b9f6-beb41a6831a0"), 0, 1)).isEmpty();
    }

    @Test
    void persists_compaction_without_replacing_raw_history_and_durably_limits_overflow_recovery() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "compaction", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("google-genai"), "gemini-3.1-flash-lite", 1_048_576);
        ConversationStore.CompactionData compact = new ConversationStore.CompactionData(1,
                com.java.system.sessionagent.conversation.domain.ContextCompaction.Reason.OVERFLOW, "historical summary",
                new SessionSequence(1), descriptor, "a".repeat(64), 100, 10);
        store.updateProgress(claim, new JobProgress(JobPhase.HISTORY_COMMIT, NOW, Optional.empty()));
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow().progress())
                .contains(new JobProgress(JobPhase.HISTORY_COMMIT, NOW, Optional.empty()));

        store.compact(claim, compact, NOW);

        ConversationStore restarted = store();
        assertThat(restarted.readJob(receipt.messageJobId()).orElseThrow().progress())
                .contains(new JobProgress(JobPhase.PREPARING, NOW, Optional.empty()));
        assertThat(restarted.loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L);
        assertThat(restarted.loadCompaction(receipt.sessionId())).hasValueSatisfying(value -> {
            assertThat(value.summary().text()).isEqualTo("historical summary");
            assertThat(value.reason()).isEqualTo(com.java.system.sessionagent.conversation.domain.ContextCompaction.Reason.OVERFLOW);
        });
        assertThat(restarted.hasOverflowCompaction(receipt.messageJobId())).isTrue();
        assertThatThrownBy(() -> restarted.compact(claim, new ConversationStore.CompactionData(2,
                com.java.system.sessionagent.conversation.domain.ContextCompaction.Reason.OVERFLOW, "another summary", new SessionSequence(1),
                descriptor, "b".repeat(64), 100, 10), NOW.plusSeconds(1))).isInstanceOf(ConversationStoreFailure.class);
    }

    @Test
    void keeps_unique_ascending_raw_history_after_multiple_compactions_and_reads_the_latest_job_projection() {
        ConversationStore store = store();
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("google-genai"), "gemini-3.1-flash-lite", 1_048_576);
        MessageReceipt first = receive(store, new IncomingMessage("thread", "alice", "source-1", "first"));
        MessageWorkClaim firstClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.compact(firstClaim, new ConversationStore.CompactionData(1,
                com.java.system.sessionagent.conversation.domain.ContextCompaction.Reason.THRESHOLD, "first summary", new SessionSequence(1),
                descriptor, "a".repeat(64), 100, 10), NOW);
        store.append(firstClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("first answer")),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.ANSWER), NOW);

        MessageReceipt second = receive(store, new IncomingMessage("thread", "alice", "source-2", "second"));
        MessageWorkClaim secondClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.compact(secondClaim, new ConversationStore.CompactionData(2,
                com.java.system.sessionagent.conversation.domain.ContextCompaction.Reason.THRESHOLD, "second summary", new SessionSequence(2),
                descriptor, "b".repeat(64), 100, 10), NOW.plusSeconds(1));
        store.append(secondClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("second answer")),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.ANSWER), NOW.plusSeconds(1));

        ConversationStore restarted = store();
        assertThat(restarted.loadHistory(first.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(restarted.readSession(first.sessionId())).hasValueSatisfying(session -> {
            assertThat(session.currentJob()).hasValueSatisfying(job -> assertThat(job.messageJobId().value())
                    .isEqualTo(second.messageJobId().value()));
            assertThat(session.currentJob()).hasValueSatisfying(job -> assertThat(job.status()).isEqualTo(JobStatus.DONE));
        });
        assertThat(restarted.loadCompaction(first.sessionId())).hasValueSatisfying(compaction -> {
            assertThat(compaction.generation()).isEqualTo(2);
            assertThat(compaction.summary().text()).isEqualTo("second summary");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "tool"})
    void counts_user_arriving_after_request_snapshot_before_response_commit_when_deciding_compaction(String arrivalPhase) {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("checkpoint-race", "alice", "first", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("test"), "bounded", 12_000);
        String interveningMessage = "x".repeat(40_000);
        List<String> calls = new ArrayList<>();
        Runnable intake = () -> receive(store,
                new IncomingMessage("checkpoint-race", "alice", "intervening", interveningMessage));
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(new ToolBinding(
                new ToolDefinition(new ToolName("opaque"), "opaque", Map.of()),
                (context, arguments) -> {
                    if (arrivalPhase.equals("tool")) {
                        intake.run();
                    }
                    return new ToolOutput(false, Map.of("value", "observed"));
                }, ToolCapability.ORDINARY)));
        AtomicInteger answers = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
                throw new AssertionError("Unexpected intent request");
            }
            @Override public ModelRouteId routeId() { return descriptor.routeId(); }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "system"; }
            @Override public String summarize(ContextCompactionRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
                calls.add("summary");
                assertThat(request.history()).filteredOn(UserMessage.class::isInstance)
                        .extracting(message -> ((UserMessage) message).message()).contains(interveningMessage);
                reservation.reserve();
                return "summary including intervening user";
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observer) {
                calls.add("answer");
                reservation.reserve();
                if (answers.incrementAndGet() == 1) {
                    assertThat(request.history()).hasSize(1);
                    if (arrivalPhase.equals("model")) {
                        intake.run();
                    }
                    return new ModelCallResult(new ModelReply.UseTools(Optional.empty(), List.of(
                            new ToolRequest(new ToolCallId("lookup"), new ToolName("opaque"), Map.of()))),
                            Optional.empty(), new ModelUsage(50, 20, 70, true));
                }
                return new ModelCallResult(new ModelReply.Text("done"), Optional.empty());
            }
        };
        JobProcessor processor = new JobProcessor(store, TestConfiguredModels.withModel(model), catalog,
                new FileSystemSkillLibrary(new SkillProperties()), Clock.fixed(NOW, ZoneOffset.UTC), 3,
                new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3, Duration.ofSeconds(1)),
                new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());

        assertThat(processor.process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.COMPLETED);

        assertThat(calls).containsExactly("answer", "summary", "answer");
        assertThat(store().loadCompaction(receipt.sessionId())).hasValueSatisfying(compaction -> {
            assertThat(compaction.estimateBeforeTokens()).isGreaterThanOrEqualTo(10_000);
            assertThat(compaction.coveredThrough().value()).isEqualTo(4);
        });
        assertThat(store().loadHistory(receipt.sessionId())).extracting(SessionMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT_TOOL_CALLS,
                        MessageRole.TOOL, MessageRole.ASSISTANT);
        assertThat(store.readJob(receipt.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void persists_available_model_usage_with_the_committed_response_boundary() {
        ConversationStore store = store();
        ModelSelection selection = new ModelSelection("test", "v1:test");
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"), selection,
                model -> Optional.of(selection));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("google-genai"), "gemini-3.1-flash-lite", 1_048_576);
        ModelUsage usage = new ModelUsage(50, 20, 70, true);
        ConversationModel model = new ConversationModel() {
            @Override public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
                throw new AssertionError("Unexpected intent request in ordinary-job fixture");
            }
            @Override public ModelRouteId routeId() { return descriptor.routeId(); }
            @Override public ModelDescriptor descriptor() { return descriptor; }
            @Override public String systemPrompt() { return "Runtime system prompt"; }
            @Override public ModelCallResult respond(
                    ModelRequest request,
                    ModelCallReservation reservation,
                    Consumer<ModelCallObservation> usageObserver) {
                reservation.reserve();
                return new ModelCallResult(new ModelReply.Text("done"), Optional.empty(), usage);
            }
        };
        ToolCatalog toolCatalog = () -> new ToolSnapshot(List.of());
        JobProcessor service = new JobProcessor(store, TestConfiguredModels.withModel(model), toolCatalog, new FileSystemSkillLibrary(new SkillProperties()),
                Clock.fixed(NOW, ZoneOffset.UTC), 3, new com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy(3,
                Duration.ofSeconds(1)), new com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry());

        service.process(claim, () -> true);

        ConversationStore restartedStore = store();
        List<SessionMessage> history = restartedStore.loadHistory(receipt.sessionId());
        ContextUsageEstimator estimator = new ContextUsageEstimator();
        String fingerprint;
        try (ToolSnapshot tools = new ToolSnapshot(List.of())) {
            fingerprint = estimator.requestShapeFingerprint(new ContextUsageProjection(descriptor, model.systemPrompt(),
                    List.of(), history.subList(0, 1), 0, Optional.empty(),
                    estimator.eligibilityFingerprint(new ContextHistoryView(
                            restartedStore.loadContextHistory(receipt.sessionId())).eligibilityFingerprint(), tools)));
        }
        assertThat(history).extracting(message -> message.sequence().value()).containsExactly(1L, 2L);
        assertThat(history.get(1)).isInstanceOfSatisfying(AssistantMessage.class,
                assistant -> assertThat(assistant.message()).isEqualTo("done"));
        assertThat(restartedStore.loadUsageCheckpoint(receipt.sessionId(), selection, descriptor, fingerprint, 0))
                .hasValueSatisfying(checkpoint -> {
                    assertThat(checkpoint.responseBoundary().value()).isEqualTo(2L);
                    assertThat(checkpoint.modelCallOrdinal()).isEqualTo(1);
                    assertThat(checkpoint.promptTokens()).isEqualTo(50);
                    assertThat(checkpoint.completionTokens()).isEqualTo(20);
                    assertThat(checkpoint.totalTokens()).isEqualTo(70);
                    ContextUsageProjection projection;
                    try (ToolSnapshot tools = new ToolSnapshot(List.of())) {
                        projection = new ContextUsageProjection(descriptor, model.systemPrompt(), List.of(), history,
                                0, Optional.empty(), estimator.eligibilityFingerprint(new ContextHistoryView(
                                restartedStore.loadContextHistory(receipt.sessionId())).eligibilityFingerprint(), tools));
                    }
                    assertThat(estimator.estimate(projection, Optional.of(checkpoint)).basis())
                            .isEqualTo(ContextEstimate.Basis.PROVIDER_PLUS_TRAILING_ESTIMATE);
                });
    }

    @Test
    void restores_current_job_continuation_after_runtime_reconstruction_and_removes_it_on_completion() {
        ConversationStore originalStore = store();
        MessageReceipt receipt = receive(originalStore, new IncomingMessage("thread", "alice", "continuation", "hello"));
        MessageWorkClaim claim = originalStore.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1, 2, 3});
        originalStore.bindModelRoute(claim, continuation.modelRouteId());
        originalStore.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(java.util.Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "mcp_lookup", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "mcp_lookup", Map.of("isError", false, "result", Map.of()))),
                ConversationStore.JobUpdate.KEEP_WORKING, java.util.Optional.of(continuation), ConversationStore.CompletionDisposition.NONE), NOW);

        ConversationStore reconstructedStore = store();
        assertThat(reconstructedStore.loadContinuations(claim)).containsOnlyKeys(new com.java.system.sessionagent.conversation.domain.SessionSequence(2));
        assertThat(reconstructedStore.loadContinuations(claim).get(new com.java.system.sessionagent.conversation.domain.SessionSequence(2)))
                .isEqualTo(continuation);

        reconstructedStore.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.ANSWER), NOW.plusSeconds(1));

        assertThat(reconstructedStore.loadContinuations(claim)).isEmpty();
        assertThat(reconstructedStore.loadHistory(receipt.sessionId())).extracting(message -> message.role())
                .containsExactly(com.java.system.sessionagent.conversation.domain.MessageRole.USER,
                        com.java.system.sessionagent.conversation.domain.MessageRole.ASSISTANT_TOOL_CALLS,
                        com.java.system.sessionagent.conversation.domain.MessageRole.TOOL,
                        com.java.system.sessionagent.conversation.domain.MessageRole.ASSISTANT);
    }

    @Test
    void rejects_a_nonterminal_append_when_its_claim_expires_while_waiting_for_session_sequence_allocation() throws Exception {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(1)).orElseThrow();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select session_id from conversation_session where session_id = ? for update")) {
                statement.setObject(1, java.util.UUID.fromString(claim.sessionId().value()));
                statement.executeQuery();
            }
            Future<?> append = executor.submit(() -> store.append(claim,
                    new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("still working")),
                            ConversationStore.JobUpdate.KEEP_WORKING, ConversationStore.CompletionDisposition.NONE), NOW));
            waitForSessionLock();
            try (PreparedStatement statement = connection.prepareStatement("select pg_sleep(1.1)")) {
                statement.executeQuery();
            }
            connection.commit();

            assertThatThrownBy(append::get).hasCauseInstanceOf(StaleWorkClaimException.class);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class,
                java.util.UUID.fromString(receipt.messageJobId().value()))).isEqualTo("WORKING");
    }

    @Test
    void protects_committed_message_details_from_later_update_or_delete() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("original")),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.ANSWER), NOW);

        assertThatThrownBy(() -> jdbcTemplate.update("update assistant_message set message = 'changed'"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("delete from assistant_message"))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L, 2L);
    }

    @Test
    void rolls_back_the_entire_native_batch_when_a_later_tool_result_insert_fails() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.execute("""
                create function fail_test_tool_detail() returns trigger language plpgsql as $$
                begin
                    raise exception 'forced tool detail failure';
                end;
                $$;
                create trigger fail_test_tool_detail before insert on tool_observation
                    for each row execute function fail_test_tool_detail();
                """);

        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1, 2, 3});
        store.bindModelRoute(claim, continuation.modelRouteId());
        ModelDescriptor descriptor = new ModelDescriptor(continuation.modelRouteId(), "gemini-3.1-flash-lite", 1_048_576);
        assertThatThrownBy(() -> store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(java.util.Optional.of("Checking."), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "mcp_lookup", Map.of()),
                        new ConversationStore.ToolCallData(new ToolCallId("call-2"), "mcp_entries", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "mcp_lookup", Map.of("isError", false, "result", Map.of())),
                new ConversationStore.ToolObservationData(new ToolCallId("call-2"), "mcp_entries", Map.of("isError", false, "result", Map.of()))),
                ConversationStore.JobUpdate.KEEP_WORKING, java.util.Optional.of(continuation), java.util.Optional.of(
                        new ConversationStore.UsageCheckpointData(claim.modelSelection(), descriptor, 1, 50, 20, 70,
                                "a".repeat(64), 0, new SessionSequence(1))), ConversationStore.CompletionDisposition.NONE), NOW)).isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from assistant_message", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from assistant_tool_calls", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from tool_observation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from model_continuation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from context_usage_checkpoint", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class,
                java.util.UUID.fromString(receipt.messageJobId().value()))).isEqualTo("WORKING");
    }

    @Test
    void automatic_skill_outcome_rolls_back_with_a_later_observation_and_retries_with_the_same_atomic_link() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "read instructions"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticSkillOutput(store, claim);
        ConversationStore.MessageBatch batch = skillBatch(output);
        jdbcTemplate.execute("""
                create function fail_after_skill() returns trigger language plpgsql as $$
                begin if new.tool_call_id='later' then raise exception 'later result failed'; end if; return new; end;
                $$;
                create trigger fail_after_skill before insert on tool_observation
                    for each row execute function fail_after_skill();
                """);

        assertThatThrownBy(() -> store.append(claim, batch, NOW)).isInstanceOf(ConversationStoreFailure.class);
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select status from skill_load_attempt", String.class)).isEqualTo("STARTED");
        assertThat(jdbcTemplate.queryForObject("select history_sequence from skill_load_attempt", Long.class)).isNull();
        jdbcTemplate.execute("drop trigger fail_after_skill on tool_observation");
        jdbcTemplate.execute("drop function fail_after_skill()");

        store.append(claim, batch, NOW);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from skill_load_attempt load join session_message message
                    on message.session_id=load.session_id and message.sequence=load.history_sequence
                where load.status='COMMITTED_SUCCESS' and message.role='TOOL'
                    and message.message_job_id=load.message_job_id
                """, Integer.class)).isOne();
        assertThat(store().loadHistory(receipt.sessionId())).hasSize(4);
        assertThat(jdbcTemplate.queryForObject("select output::text from tool_observation where tool_call_id='skill'", String.class))
                .doesNotContain(output.skillReadReceipt().orElseThrow().attemptId().toString(), "skillReadReceipt");
    }

    @Test
    void cancellation_preserves_a_truly_committed_automatic_skill_outcome_but_rejects_new_starts() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "read instructions"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticSkillOutput(store, claim);
        store.requestCancellation(receipt.messageJobId());

        assertThat(store.append(claim, skillBatch(output), NOW)).isEqualTo(JobStatus.CANCELLED);
        assertThat(jdbcTemplate.queryForObject("select status from skill_load_attempt", String.class)).isEqualTo("COMMITTED_SUCCESS");
        assertThat(jdbcTemplate.queryForObject("select history_sequence from skill_load_attempt", Long.class)).isNotNull();
        assertThat(store.loadHistory(receipt.sessionId())).anyMatch(ToolObservation.class::isInstance);
        assertThatThrownBy(() -> new PostgresSkillLoadObserver(dataSource()).started(
                new ToolInvocation(new ToolExecutionContext(UUID.fromString(claim.sessionId().value()),
                        UUID.fromString(claim.messageJobId().value()), 1, 1),
                        new ToolExecutionAuthority(claim.workerId(), claim.claimNumber())), "skill://review"))
                .isInstanceOf(StaleWorkClaimException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from skill_load_attempt", Integer.class)).isOne();
    }

    private ToolOutput automaticSkillOutput(ConversationStore store, MessageWorkClaim claim) {
        store.reserveModelCall(claim, ModelCallPurpose.EXECUTION, 12, NOW);
        ToolInvocation invocation = new ToolInvocation(new ToolExecutionContext(UUID.fromString(claim.sessionId().value()),
                UUID.fromString(claim.messageJobId().value()), 1, 0),
                new ToolExecutionAuthority(claim.workerId(), claim.claimNumber()));
        UUID attempt = new PostgresSkillLoadObserver(dataSource()).started(invocation, "skill://review");
        SkillResource resource = new SkillResource("skill://review", "a".repeat(64), "PRIVATE_SKILL_BODY");
        SkillReadReceipt receipt = SkillReadReceipt.succeeded(attempt, resource.uri(), resource.uri(), resource.sha256());
        return new ToolOutput(false, Map.of("uri", resource.uri(), "sha256", resource.sha256(), "content", resource.content()),
                Optional.empty(), Optional.of(receipt));
    }

    private static ConversationStore.MessageBatch skillBatch(ToolOutput output) {
        ToolCallId skill = new ToolCallId("skill");
        ToolCallId later = new ToolCallId("later");
        return new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(skill, "readSkill", Map.of("uri", "skill://review")),
                        new ConversationStore.ToolCallData(later, "opaque", Map.of()))),
                new ConversationStore.ToolObservationData(skill, "readSkill", output.asStructuredValue(), Optional.empty(), output.skillReadReceipt()),
                new ConversationStore.ToolObservationData(later, "opaque", Map.of("result", "ordinary result"))),
                ConversationStore.JobUpdate.KEEP_WORKING, ConversationStore.CompletionDisposition.NONE);
    }

    @Test
    void automatic_receipt_cannot_be_attached_to_a_different_tool_position() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "read instructions"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        ToolOutput output = automaticSkillOutput(store, claim);
        ToolCallId ordinary = new ToolCallId("ordinary");
        ToolCallId skill = new ToolCallId("skill");
        ConversationStore.MessageBatch swapped = new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(ordinary, "opaque", Map.of()),
                        new ConversationStore.ToolCallData(skill, "readSkill", Map.of("uri", "skill://review")))),
                new ConversationStore.ToolObservationData(ordinary, "opaque", Map.of("result", "ordinary")),
                new ConversationStore.ToolObservationData(skill, "readSkill", output.asStructuredValue(),
                        Optional.empty(), output.skillReadReceipt())),
                ConversationStore.JobUpdate.KEEP_WORKING, ConversationStore.CompletionDisposition.NONE);

        assertThatThrownBy(() -> store.append(claim, swapped, NOW)).isInstanceOf(ConversationStoreFailure.class);
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select status from skill_load_attempt", String.class)).isEqualTo("STARTED");
        assertThat(jdbcTemplate.queryForObject("select history_sequence from skill_load_attempt", Long.class)).isNull();
    }

    @Test
    void rejects_whitespace_only_tool_and_runtime_fields_before_they_can_poison_history() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "JDBC data source must not be null");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(claim.messageJobId().value());
        Timestamp createdAt = Timestamp.from(NOW);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, 2, ?, 'TOOL', ?)",
                    sessionId, jobId, createdAt);
            jdbcTemplate.update("insert into tool_observation(session_id, sequence, tool_call_id, tool_name, output) values (?, 2, 'call-1', '   ', '{}')",
                    sessionId);
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("tool_observation_tool_name_check");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, 2, ?, 'RUNTIME', ?)",
                    sessionId, jobId, createdAt);
            jdbcTemplate.update("insert into runtime_message(session_id, sequence, code, message) values (?, 2, '   ', 'message')", sessionId);
        })).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, 2, ?, 'RUNTIME', ?)",
                    sessionId, jobId, createdAt);
            jdbcTemplate.update("insert into runtime_message(session_id, sequence, code, message) values (?, 2, 'CODE', '   ')", sessionId);
        })).isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from tool_observation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from runtime_message", Integer.class)).isZero();
    }

    @Test
    void classifies_malformed_persisted_native_call_arrays_as_invalid_history() {
        List<String> malformedCalls = List.of(
                "[{\"toolCallId\":null,\"toolName\":\"mcp_lookup\",\"arguments\":{}}]",
                "[{\"toolCallId\":\"   \",\"toolName\":\"mcp_lookup\",\"arguments\":{}}]",
                "[{\"toolCallId\":\"call-1\",\"toolName\":\"not portable!\",\"arguments\":{}}]",
                "[{\"toolCallId\":\"call-1\",\"toolName\":\"mcp_lookup\",\"arguments\":null}]",
                "[1]");
        int index = 0;
        for (String calls : malformedCalls) {
            index++;
            MessageReceipt receipt = receive(store(), new IncomingMessage("thread-" + index, "alice", "source-" + index, "hello"));
            MessageWorkClaim claim = store().claimNext("worker-" + index, Duration.ofSeconds(30)).orElseThrow();
            insertMalformedAssistantCalls(receipt, claim, calls);

            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> store().loadHistory(receipt.sessionId()));
            assertThat(failure).isInstanceOf(ConversationStoreFailure.class);
            assertThat(((ConversationStoreFailure) failure).kind()).isEqualTo(ConversationStoreFailure.Kind.INVALID_HISTORY);
        }
    }

    @Test
    void exposes_cross_job_native_tool_corruption_to_the_terminal_history_projector() {
        ConversationStore store = store();
        MessageReceipt firstReceipt = receive(store, new IncomingMessage("thread", "alice", "source-1", "first"));
        MessageReceipt secondReceipt = receive(store, new IncomingMessage("thread", "alice", "source-2", "second"));
        MessageWorkClaim firstClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        UUID sessionId = UUID.fromString(firstReceipt.sessionId().value());
        UUID firstJobId = UUID.fromString(firstClaim.messageJobId().value());
        UUID secondJobId = UUID.fromString(secondReceipt.messageJobId().value());
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "JDBC data source must not be null");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, 3, ?, 'ASSISTANT_TOOL_CALLS', ?)",
                    sessionId, firstJobId, Timestamp.from(NOW));
            jdbcTemplate.update("insert into assistant_tool_calls(session_id, sequence, message, calls) values (?, 3, null, ?::jsonb)",
                    sessionId, "[{\"toolCallId\":\"call-1\",\"toolName\":\"mcp_lookup\",\"arguments\":{}}]");
            jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, 4, ?, 'TOOL', ?)",
                    sessionId, secondJobId, Timestamp.from(NOW));
            jdbcTemplate.update("insert into tool_observation(session_id, sequence, tool_call_id, tool_name, output) values (?, 4, 'call-1', 'mcp_lookup', '{}'::jsonb)",
                    sessionId);
            jdbcTemplate.update("update conversation_session set next_sequence = next_sequence + 1 where session_id = ?", sessionId);
            jdbcTemplate.update("update conversation_session set next_sequence = next_sequence + 1 where session_id = ?", sessionId);
        });

        assertThatThrownBy(() -> new HistoryProjector(new ObjectMapper()).project(store.loadHistory(firstReceipt.sessionId())))
                .isInstanceOf(InvalidConversationHistoryException.class);
        assertThat(store.readJob(firstClaim.messageJobId())).hasValueSatisfying(job -> assertThat(job.status()).isEqualTo(JobStatus.WORKING));
    }

    private void insertMalformedAssistantCalls(MessageReceipt receipt, MessageWorkClaim claim, String calls) {
        insertMalformedAssistantCalls(receipt, claim, 2, calls);
    }

    private void insertMalformedAssistantCalls(MessageReceipt receipt, MessageWorkClaim claim, long sequence, String calls) {
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "JDBC data source must not be null");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(claim.messageJobId().value());
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, ?, ?, 'ASSISTANT_TOOL_CALLS', ?)",
                    sessionId, sequence, jobId, Timestamp.from(NOW));
            jdbcTemplate.update("insert into assistant_tool_calls(session_id, sequence, message, calls) values (?, ?, null, ?::jsonb)",
                    sessionId, sequence, calls);
        });
    }

    private void waitForSessionLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*)
                    from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock'
                    """, Integer.class);
            if (Objects.requireNonNull(waiting, "Waiting connection count must not be null") > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Append did not wait for the session lock");
    }

    private ConversationStore store() {
        return new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    private static MessageReceipt receive(ConversationStore store, IncomingMessage incomingMessage) {
        return store.receive(incomingMessage, TEST_SELECTION, model -> Optional.of(TEST_SELECTION));
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
