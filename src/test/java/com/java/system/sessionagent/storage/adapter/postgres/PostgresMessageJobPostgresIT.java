package com.java.system.sessionagent.storage.adapter.postgres;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.ContextCompaction;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.JobCancellation;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobPhase;
import com.java.system.sessionagent.conversation.domain.JobProgress;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply.UseTools;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.ModelRouteMismatchException;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.worker.application.MessageJobWorker;
import com.java.system.sessionagent.worker.configuration.WorkerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageJobPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:01Z");
    private static final ModelSelection TEST_SELECTION = new ModelSelection("test", "v1:test");
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        flyway().clean();
        flyway().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void cancels_waiting_and_retrying_jobs_once_and_unblocks_the_next_fifo_job() {
        ConversationStore store = store();
        MessageReceipt first = receive(store, new IncomingMessage("cancel-thread", "alice", "cancel-first", "one"));
        MessageReceipt second = receive(store, new IncomingMessage("cancel-thread", "alice", "cancel-second", "two"));

        ConversationStore.MessageJobProjection cancelled = store.requestCancellation(first.messageJobId()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.cancellationRequestedAt()).isPresent();
        assertThat(cancelled.failure()).isEmpty();
        assertThat(store.requestCancellation(first.messageJobId())).contains(cancelled);
        MessageWorkClaim next = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(next.messageJobId()).isEqualTo(second.messageJobId());
        assertThat(store.scheduleRetry(next, Duration.ofDays(1), JobFailure.MODEL_UNAVAILABLE)).isTrue();
        assertThat(store.requestCancellation(second.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(store.claimNext("worker", Duration.ofSeconds(30))).isEmpty();
        assertThat(store.loadHistory(first.sessionId()).stream().filter(RuntimeMessage.class::isInstance)
                .map(RuntimeMessage.class::cast)).extracting(RuntimeMessage::code)
                .containsExactly(JobCancellation.CODE, JobCancellation.CODE);
        assertThat(store.requestCancellation(new MessageJobId(
                UUID.randomUUID().toString()))).isEmpty();
    }

    @Test
    void fences_admission_and_final_commit_without_clearing_the_inflight_phase() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("cancel-thread", "alice", "cancel", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        JobProgress progress = new JobProgress(JobPhase.TOOL_EXECUTION, NOW, Optional.of("first"));
        store.updateProgress(claim, progress);

        ConversationStore.MessageJobProjection requested = store.requestCancellation(receipt.messageJobId()).orElseThrow();
        assertThat(requested.status()).isEqualTo(JobStatus.WORKING);
        assertThat(requested.progress()).contains(progress);
        assertThat(store.requestCancellation(receipt.messageJobId())).contains(requested);
        assertThat(store.isCancellationRequested(claim)).isTrue();
        assertThatThrownBy(() -> store.reserveModelCall(claim, 12, NOW))
                .isInstanceOf(JobCancellationRequestedException.class);
        assertThatThrownBy(() -> store.updateProgress(claim, progress))
                .isInstanceOf(JobCancellationRequestedException.class);
        assertThatThrownBy(() -> store.scheduleRetry(claim, Duration.ZERO, JobFailure.MODEL_UNAVAILABLE))
                .isInstanceOf(JobCancellationRequestedException.class);
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("test"), "fake", 32_000);
        String shape = "c".repeat(64);
        assertThatThrownBy(() -> store.compact(claim, new ConversationStore.CompactionData(
                1, ContextCompaction.Reason.THRESHOLD, "summary", new SessionSequence(1), descriptor, shape, 20, 10), NOW))
                .isInstanceOf(JobCancellationRequestedException.class);
        store.updateProgress(claim, new JobProgress(JobPhase.HISTORY_COMMIT, NOW, Optional.empty()));
        assertThat(store.append(claim, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.AssistantData("must not be committed")), ConversationStore.JobUpdate.COMPLETE,
                Optional.empty(), Optional.of(new ConversationStore.UsageCheckpointData(
                        TEST_SELECTION, descriptor, 1, 20, 10, 30, shape, 0))), NOW))
                .isEqualTo(JobStatus.CANCELLED);
        assertThat(store().loadHistory(receipt.sessionId())).extracting(SessionMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.RUNTIME);
        assertThat(store.loadUsageCheckpoint(receipt.sessionId(), TEST_SELECTION, descriptor, shape, 0)).isEmpty();
        assertThat(store.loadCompaction(receipt.sessionId())).isEmpty();
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.failure()).isEmpty();
            assertThat(job.progress()).isEmpty();
            assertThat(job.modelCallCount()).isZero();
        });
        assertThatThrownBy(() -> store.append(claim, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.RuntimeData(JobCancellation.CODE, JobCancellation.MESSAGE)),
                ConversationStore.JobUpdate.CANCEL), NOW)).isInstanceOf(StaleWorkClaimException.class);
        assertThat(store.requestCancellation(receipt.messageJobId())).contains(store.readJob(receipt.messageJobId()).orElseThrow());
    }

    @Test
    void remembers_requested_cancellation_after_expiry_and_reclaim_without_calling_the_model() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("cancel-thread", "alice", "restart", "hello"));
        MessageWorkClaim expired = store.claimNext("old", Duration.ofSeconds(30)).orElseThrow();
        Instant requestedAt = store.requestCancellation(receipt.messageJobId()).orElseThrow()
                .cancellationRequestedAt().orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 second' where message_job_id = ?",
                UUID.fromString(receipt.messageJobId().value()));
        ConversationStore restarted = store();
        MessageWorkClaim recovered = restarted.claimNext("new", Duration.ofSeconds(30)).orElseThrow();
        AtomicInteger modelCalls = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return new ModelRouteId("test");
            }

            @Override
            public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> observer) {
                modelCalls.incrementAndGet();
                throw new AssertionError("Cancelled reclaimed jobs must not call the provider");
            }
        };
        JobProcessor processor = new JobProcessor(restarted, TestConfiguredModels.withModel(model),
                () -> new ToolSnapshot(List.of()), Clock.systemUTC(), 3,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());

        assertThat(processor.process(recovered, () -> true)).isEqualTo(MessageJobProcessingResult.CANCELLED);
        assertThat(modelCalls).hasValue(0);
        assertThat(restarted.readJob(receipt.messageJobId()).orElseThrow().cancellationRequestedAt()).contains(requestedAt);
        assertThatThrownBy(() -> store.isCancellationRequested(expired)).isInstanceOf(StaleWorkClaimException.class);
        assertThatThrownBy(() -> store.append(expired, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.AssistantData("stale")), ConversationStore.JobUpdate.COMPLETE), NOW))
                .isInstanceOf(StaleWorkClaimException.class);
    }

    @Test
    void finishes_only_the_current_tool_and_atomically_publishes_real_and_cancelled_observations() throws Exception {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("cancel-thread", "alice", "tools", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(2)).orElseThrow();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return new ModelRouteId("test");
            }

            @Override
            public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> observer) {
                reservation.reserve();
                modelCalls.incrementAndGet();
                return new ModelCallResult(new UseTools(Optional.of("checking"), List.of(
                        new ToolRequest(new ToolCallId("first"), new ToolName("first"), Map.of()),
                        new ToolRequest(new ToolCallId("second"), new ToolName("second"), Map.of()))), Optional.empty());
            }
        };
        ToolCatalog tools = () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()), arguments -> {
                    firstStarted.countDown();
                    awaitGate(releaseFirst);
                    return new ToolOutput(false, Map.of("result", "actual first result"));
                }),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()), arguments -> {
                    secondCalls.incrementAndGet();
                    return new ToolOutput(false, Map.of("unexpected", true));
                })));
        JobProcessor processor = new JobProcessor(store, TestConfiguredModels.withModel(model), tools, Clock.systemUTC(), 3,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MessageJobProcessingResult> result = executor.submit(() -> processor.process(claim, () -> true));
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            store().requestCancellation(receipt.messageJobId());
            assertLivePhase(receipt, JobPhase.TOOL_EXECUTION, Optional.of("first"));
            assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
            try (Connection connection = dataSource().getConnection();
                    PreparedStatement lock = connection.prepareStatement(
                            "select session_id from conversation_session where session_id = ? for update")) {
                connection.setAutoCommit(false);
                lock.setObject(1, UUID.fromString(receipt.sessionId().value()));
                lock.executeQuery();
                releaseFirst.countDown();
                awaitDatabaseLockWaiter("next_sequence = next_sequence + 1");
                assertLivePhase(receipt, JobPhase.HISTORY_COMMIT, Optional.empty());
                assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
                connection.rollback();
            }
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(MessageJobProcessingResult.CANCELLED);
            assertThat(modelCalls).hasValue(1);
            assertThat(secondCalls).hasValue(0);
            List<SessionMessage> history = store().loadHistory(receipt.sessionId());
            assertThat(history).extracting(SessionMessage::role).containsExactly(MessageRole.USER,
                    MessageRole.ASSISTANT_TOOL_CALLS, MessageRole.TOOL, MessageRole.TOOL, MessageRole.RUNTIME);
            assertThat(((AssistantToolCallsMessage) history.get(1)).message())
                    .contains("checking");
            assertThat(((ToolObservation) history.get(2)).toolCallId()).isEqualTo(new ToolCallId("first"));
            assertThat(((ToolObservation) history.get(2)).output())
                    .isEqualTo(Map.of("isError", false, "result", Map.of("result", "actual first result")));
            assertThat(((ToolObservation) history.get(3)).toolCallId()).isEqualTo(new ToolCallId("second"));
            assertThat(((ToolObservation) history.get(3)).output()).isEqualTo(
                    ToolOutput.runtimeFailure(JobCancellation.TOOL_CODE, JobCancellation.TOOL_MESSAGE).asStructuredValue());
            assertThat(((RuntimeMessage) history.get(4)).code()).isEqualTo(JobCancellation.CODE);
            assertThat(store.requestCancellation(receipt.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
            assertThat(store().loadHistory(receipt.sessionId())).hasSize(5);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void leaves_an_already_completed_job_and_its_history_unchanged() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("cancel-thread", "alice", "completed", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), NOW)).isEqualTo(JobStatus.DONE);
        assertThat(store.requestCancellation(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.DONE);
            assertThat(job.cancellationRequestedAt()).isEmpty();
        });
        assertThat(store.loadHistory(receipt.sessionId())).extracting(SessionMessage::role)
                .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
    }

    @Test
    void serializes_cancellation_before_an_already_waiting_final_commit() throws Exception {
        DriverManagerDataSource source = dataSource();
        ConversationStore store = new PostgresConversationStore(source, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        MessageReceipt receipt = receive(store, new IncomingMessage("race-thread", "alice", "race", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<JobStatus>> result = new AtomicReference<>();
        try {
            transaction.executeWithoutResult(status -> {
                store.requestCancellation(receipt.messageJobId());
                result.set(executor.submit(() -> store.append(claim, new ConversationStore.MessageBatch(
                        List.of(new ConversationStore.AssistantData("late final response")),
                        ConversationStore.JobUpdate.COMPLETE), NOW)));
                awaitDatabaseLockWaiter("locked_until = locked_until");
                assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
            });
            assertThat(result.get().get(5, TimeUnit.SECONDS)).isEqualTo(JobStatus.CANCELLED);
            assertThat(store().loadHistory(receipt.sessionId())).extracting(SessionMessage::role)
                    .containsExactly(MessageRole.USER, MessageRole.RUNTIME);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void preserves_completed_first_when_cancellation_waits_for_the_atomic_history_transaction() throws Exception {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("race-thread", "alice", "complete-first", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection connection = dataSource().getConnection();
                PreparedStatement lock = connection.prepareStatement(
                        "select session_id from conversation_session where session_id = ? for update")) {
            connection.setAutoCommit(false);
            lock.setObject(1, UUID.fromString(receipt.sessionId().value()));
            lock.executeQuery();
            Future<JobStatus> completion = executor.submit(() -> store.append(claim, new ConversationStore.MessageBatch(
                    List.of(new ConversationStore.AssistantData("done")), ConversationStore.JobUpdate.COMPLETE), NOW));
            awaitDatabaseLockWaiter("next_sequence = next_sequence + 1");
            Future<Optional<ConversationStore.MessageJobProjection>> cancellation = executor.submit(
                    () -> store.requestCancellation(receipt.messageJobId()));
            awaitDatabaseLockWaiter("cancellation_requested_at");
            assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
            connection.rollback();
            assertThat(completion.get(5, TimeUnit.SECONDS)).isEqualTo(JobStatus.DONE);
            assertThat(cancellation.get(5, TimeUnit.SECONDS).orElseThrow()).satisfies(job -> {
                assertThat(job.status()).isEqualTo(JobStatus.DONE);
                assertThat(job.cancellationRequestedAt()).isEmpty();
            });
            assertThat(store.loadHistory(receipt.sessionId())).extracting(SessionMessage::role)
                    .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rolls_back_the_cancellation_request_and_history_together_when_terminal_persistence_fails() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("rollback-thread", "alice", "rollback", "hello"));
        jdbcTemplate.execute("""
                create function reject_cancellation_terminal() returns trigger language plpgsql as $$
                begin
                    raise exception 'terminal rejected';
                end;
                $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_cancellation_terminal before insert on runtime_message
                for each row execute function reject_cancellation_terminal()
                """);
        assertThatThrownBy(() -> store.requestCancellation(receipt.messageJobId()))
                .isInstanceOf(ConversationStoreFailure.class);
        assertThat(store.readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.PENDING);
            assertThat(job.cancellationRequestedAt()).isEmpty();
        });
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(store.claimNext("worker", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void migrates_v5_history_and_live_progress_without_requesting_cancellation() {
        flyway().clean();
        Flyway.configure().dataSource(dataSource()).target("5").load().migrate();
        ConversationStore store = store();
        MessageReceipt active = receive(store, new IncomingMessage("active-v5", "alice", "active-v5", "hello"));
        MessageReceipt completed = receive(store, new IncomingMessage("done-v5", "alice", "done-v5", "hello"));
        seedLegacyTerminalHistory(completed, JobFailure.MODEL_UNAVAILABLE.name(), false);
        jdbcTemplate.update("update message_job set failure_code = 'MODEL_UNAVAILABLE' where message_job_id = ?",
                UUID.fromString(completed.messageJobId().value()));
        MessageWorkClaim claim = store.claimNext("old-worker", Duration.ofMinutes(2)).orElseThrow();
        jdbcTemplate.update("""
                update message_job set phase = 'TOOL_EXECUTION', phase_started_at = ?, current_tool_name = 'first', model_calls = 2
                where message_job_id = ?
                """, Timestamp.from(NOW), UUID.fromString(active.messageJobId().value()));
        List<SessionMessage> history = store.loadHistory(completed.sessionId());

        flyway().migrate();

        assertThat(store().readJob(active.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.WORKING);
            assertThat(job.progress()).contains(new JobProgress(JobPhase.TOOL_EXECUTION, NOW, Optional.of("first")));
            assertThat(job.modelCallCount()).isEqualTo(2);
            assertThat(job.cancellationRequestedAt()).isEmpty();
        });
        assertThat(store().readJob(completed.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.DONE);
            assertThat(job.failure()).contains(JobFailure.MODEL_UNAVAILABLE);
            assertThat(job.cancellationRequestedAt()).isEmpty();
        });
        assertThat(store.loadHistory(completed.sessionId())).isEqualTo(history);
        assertThat(store.extendClaim(claim, Duration.ofMinutes(3))).isTrue();
        assertThat(store.requestCancellation(active.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.WORKING);
    }

    @Test
    void exposes_reserved_model_work_without_publishing_partial_history() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("progress-thread", "alice", "progress-source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        assertThat(store.readJob(receipt.messageJobId()).orElseThrow().progress()).hasValueSatisfying(value -> {
            assertThat(value.phase()).isEqualTo(JobPhase.PREPARING);
            assertThat(value.toolName()).isEmpty();
        });
        assertThat(store.reserveModelCall(claim, 12, NOW)).hasValue(1);

        Map<String, Object> progress = jdbcTemplate.queryForMap("""
                select to_jsonb(job)->>'phase' as phase,
                       to_jsonb(job)->>'phase_started_at' as phase_started_at,
                       to_jsonb(job)->>'current_tool_name' as current_tool_name
                from message_job job where message_job_id = ?
                """, UUID.fromString(receipt.messageJobId().value()));
        assertThat(progress.get("phase")).isEqualTo("MODEL_CALL");
        assertThat(progress.get("phase_started_at")).isNotNull();
        assertThat(progress.get("current_tool_name")).isNull();
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow().progress())
                .contains(new JobProgress(JobPhase.MODEL_CALL, NOW, Optional.empty()));
        assertThat(store.readSession(receipt.sessionId()).orElseThrow().currentJob())
                .contains(store.readJob(receipt.messageJobId()).orElseThrow());
        assertThat(store.loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L);
    }

    @Test
    void publishes_processor_phases_while_model_tools_and_atomic_history_commit_are_blocked() throws Exception {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "gated", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofMinutes(2)).orElseThrow();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CountDownLatch firstToolStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTool = new CountDownLatch(1);
        CountDownLatch secondToolStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondTool = new CountDownLatch(1);
        CountDownLatch finalModelStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalModel = new CountDownLatch(1);
        AtomicInteger turns = new AtomicInteger();
        ConversationModel model = new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return new ModelRouteId("test");
            }

            @Override
            public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation,
                    Consumer<ModelUsage> usageObserver) {
                reservation.reserve();
                if (turns.incrementAndGet() == 1) {
                    modelStarted.countDown();
                    awaitGate(releaseModel);
                    return new ModelCallResult(new UseTools(Optional.of("checking"), List.of(
                            new ToolRequest(new ToolCallId("first"), new ToolName("first"), Map.of()),
                            new ToolRequest(new ToolCallId("second"), new ToolName("second"), Map.of()))), Optional.empty());
                }
                finalModelStarted.countDown();
                awaitGate(releaseFinalModel);
                return new ModelCallResult(new ModelReply.Text("done"), Optional.empty());
            }
        };
        ToolCatalog tools = () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()), arguments -> {
                    firstToolStarted.countDown();
                    awaitGate(releaseFirstTool);
                    return new ToolOutput(false, Map.of("result", "first"));
                }),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()), arguments -> {
                    secondToolStarted.countDown();
                    awaitGate(releaseSecondTool);
                    return new ToolOutput(true, Map.of("error", "ordinary tool failure"));
                })));
        JobProcessor processor = new JobProcessor(store, TestConfiguredModels.withModel(model), tools, Clock.systemUTC(), 3,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MessageJobProcessingResult> result = executor.submit(() -> processor.process(claim, () -> true));
            assertThat(modelStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertLivePhase(receipt, JobPhase.MODEL_CALL, Optional.empty());
            assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
            releaseModel.countDown();
            assertThat(firstToolStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertLivePhase(receipt, JobPhase.TOOL_EXECUTION, Optional.of("first"));
            assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
            releaseFirstTool.countDown();
            assertThat(secondToolStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertLivePhase(receipt, JobPhase.TOOL_EXECUTION, Optional.of("second"));
            assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);

            try (Connection connection = dataSource().getConnection();
                    PreparedStatement lock = connection.prepareStatement(
                            "select session_id from conversation_session where session_id = ? for update")) {
                connection.setAutoCommit(false);
                lock.setObject(1, UUID.fromString(receipt.sessionId().value()));
                lock.executeQuery();
                releaseSecondTool.countDown();
                awaitDatabaseLockWaiter("next_sequence = next_sequence + 1");
                assertLivePhase(receipt, JobPhase.HISTORY_COMMIT, Optional.empty());
                assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
                connection.rollback();
            }
            assertThat(finalModelStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertLivePhase(receipt, JobPhase.MODEL_CALL, Optional.empty());
            assertThat(store().loadHistory(receipt.sessionId())).extracting(message -> message.role())
                    .containsExactly(MessageRole.USER, MessageRole.ASSISTANT_TOOL_CALLS, MessageRole.TOOL, MessageRole.TOOL);
            releaseFinalModel.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(MessageJobProcessingResult.COMPLETED);
            assertThat(store().readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
                assertThat(job.status()).isEqualTo(JobStatus.DONE);
                assertThat(job.progress()).isEmpty();
                assertThat(job.failure()).isEmpty();
                assertThat(job.modelCallCount()).isEqualTo(2);
            });
            assertThat(store().loadHistory(receipt.sessionId())).extracting(message -> message.role())
                    .containsExactly(MessageRole.USER, MessageRole.ASSISTANT_TOOL_CALLS, MessageRole.TOOL,
                            MessageRole.TOOL, MessageRole.ASSISTANT);
        } finally {
            releaseModel.countDown();
            releaseFirstTool.countDown();
            releaseSecondTool.countDown();
            releaseFinalModel.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void clears_tool_progress_on_retry_reclaim_and_success_and_persists_safe_terminal_outcome() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "retry", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.updateProgress(claim, new JobProgress(JobPhase.TOOL_EXECUTION, NOW, Optional.of("lookup")));

        assertThat(store.scheduleRetry(claim, Duration.ZERO, JobFailure.STORAGE_UNAVAILABLE)).isTrue();
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.RETRY);
            assertThat(job.progress()).isEmpty();
            assertThat(job.failure()).contains(JobFailure.STORAGE_UNAVAILABLE);
        });
        MessageWorkClaim retry = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertLivePhase(receipt, JobPhase.PREPARING, Optional.empty());
        store.updateProgress(retry, new JobProgress(JobPhase.HISTORY_COMMIT, NOW, Optional.empty()));
        store.append(retry, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("intermediate")),
                ConversationStore.JobUpdate.KEEP_WORKING), NOW);
        assertLivePhase(receipt, JobPhase.PREPARING, Optional.empty());
        store.append(retry, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.RuntimeData(JobFailure.MODEL_OUTPUT_INVALID.name(),
                        JobFailure.MODEL_OUTPUT_INVALID.message())),
                ConversationStore.JobUpdate.KEEP_WORKING, Optional.empty(), Optional.empty(),
                Optional.of(JobFailure.MODEL_OUTPUT_INVALID)), NOW);
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.progress()).contains(new JobProgress(JobPhase.PREPARING, NOW, Optional.empty()));
            assertThat(job.failure()).contains(JobFailure.MODEL_OUTPUT_INVALID);
        });
        assertThat(store.reserveModelCall(retry, 3, NOW)).hasValue(1);
        assertLivePhase(receipt, JobPhase.MODEL_CALL, Optional.empty());
        store.append(retry, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), NOW);
        assertThat(store().readJob(receipt.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.progress()).isEmpty();
            assertThat(job.failure()).isEmpty();
        });

        MessageReceipt failed = receive(store, new IncomingMessage("thread", "alice", "terminal", "again"));
        MessageWorkClaim failedClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        JobFailure failure = JobFailure.MODEL_OUTPUT_INVALID;
        store.updateProgress(failedClaim, new JobProgress(JobPhase.HISTORY_COMMIT, NOW, Optional.empty()));
        store.append(failedClaim, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.RuntimeData(failure.name(), failure.message())),
                ConversationStore.JobUpdate.COMPLETE, Optional.empty(), Optional.empty(), Optional.of(failure)), NOW);
        ConversationStore.MessageJobProjection persisted = store().readJob(failed.messageJobId()).orElseThrow();
        assertThat(persisted.status()).isEqualTo(JobStatus.DONE);
        assertThat(persisted.progress()).isEmpty();
        assertThat(persisted.failure()).contains(failure);
        assertThat(store().readSession(failed.sessionId()).orElseThrow().currentJob()).contains(persisted);
        assertThat(jdbcTemplate.queryForObject("select failure_code from message_job where message_job_id = ?",
                String.class, UUID.fromString(failed.messageJobId().value()))).isEqualTo(failure.name());
        assertThatThrownBy(() -> jdbcTemplate.update("update message_job set failure_code = ? where message_job_id = ?",
                "raw provider error: secret", UUID.fromString(failed.messageJobId().value())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void suppresses_expired_progress_using_database_time_and_fences_writers_before_and_after_reclaim() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "expiry", "hello"));
        MessageWorkClaim original = store.claimNext("same-worker", Duration.ofSeconds(30)).orElseThrow();
        JobProgress tool = new JobProgress(JobPhase.TOOL_EXECUTION, NOW, Optional.of("lookup"));
        store.updateProgress(original, tool);
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?",
                UUID.fromString(receipt.messageJobId().value()));

        ConversationStore.MessageJobProjection expired = store().readJob(receipt.messageJobId()).orElseThrow();
        assertThat(expired.status()).isEqualTo(JobStatus.WORKING);
        assertThat(expired.progress()).isEmpty();
        assertThat(expired.failure()).contains(JobFailure.PROCESSING_INTERRUPTED);
        assertThat(store().readSession(receipt.sessionId()).orElseThrow().currentJob()).contains(expired);
        assertThatThrownBy(() -> store.updateProgress(original, tool)).isExactlyInstanceOf(StaleWorkClaimException.class);
        assertThat(store.scheduleRetry(original, Duration.ZERO, JobFailure.MODEL_UNAVAILABLE)).isFalse();
        assertThat(store.reserveModelCall(original, 3, NOW)).isEmpty();
        assertThatThrownBy(() -> store.append(original, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.AssistantData("stale")), ConversationStore.JobUpdate.COMPLETE), NOW))
                .isExactlyInstanceOf(StaleWorkClaimException.class);

        MessageWorkClaim recovered = store.claimNext("same-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.claimNumber()).isGreaterThan(original.claimNumber());
        assertLivePhase(receipt, JobPhase.PREPARING, Optional.empty());
        assertThatThrownBy(() -> store.updateProgress(original, tool)).isExactlyInstanceOf(StaleWorkClaimException.class);
        assertThat(store.scheduleRetry(original, Duration.ZERO, JobFailure.MODEL_UNAVAILABLE)).isFalse();
        assertThat(store.reserveModelCall(original, 3, NOW)).isEmpty();
        assertThat(store().loadHistory(receipt.sessionId())).hasSize(1);
    }

    @Test
    void migrates_existing_working_and_pending_rows_without_inventing_progress_and_initializes_reclaimed_work() {
        flyway().clean();
        Flyway.configure().dataSource(dataSource()).target("4").load().migrate();
        ConversationStore store = store();
        MessageReceipt active = receive(store, new IncomingMessage("active-thread", "alice", "old-active", "hello"));
        MessageReceipt expired = receive(store, new IncomingMessage("expired-thread", "alice", "old-expired", "hello"));
        MessageReceipt pending = receive(store, new IncomingMessage("pending-thread", "alice", "old-pending", "hello"));
        jdbcTemplate.update("""
                update message_job set status = 'WORKING', worker_id = 'old-worker', claim_number = 1,
                    locked_until = clock_timestamp() + interval '1 hour'
                where message_job_id = ?
                """, UUID.fromString(active.messageJobId().value()));
        jdbcTemplate.update("""
                update message_job set status = 'WORKING', worker_id = 'old-worker', claim_number = 1,
                    locked_until = clock_timestamp() - interval '1 second', available_at = clock_timestamp() - interval '1 hour'
                where message_job_id = ?
                """, UUID.fromString(expired.messageJobId().value()));

        flyway().migrate();

        assertThat(store.readJob(active.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.WORKING);
            assertThat(job.progress()).isEmpty();
            assertThat(job.failure()).isEmpty();
        });
        assertThat(store.readJob(pending.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.PENDING);
            assertThat(job.progress()).isEmpty();
            assertThat(job.failure()).isEmpty();
        });
        assertThat(store.readJob(expired.messageJobId()).orElseThrow().failure())
                .contains(JobFailure.PROCESSING_INTERRUPTED);
        MessageWorkClaim recovered = store.claimNext("new-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(recovered.messageJobId()).isEqualTo(expired.messageJobId());
        assertLivePhase(expired, JobPhase.PREPARING, Optional.empty());
        assertThat(store().loadHistory(expired.sessionId())).hasSize(1);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                update message_job set phase = 'MODEL_CALL', phase_started_at = clock_timestamp()
                where message_job_id = ?
                """, UUID.fromString(pending.messageJobId().value()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                update message_job set current_tool_name = 'lookup' where message_job_id = ?
                """, UUID.fromString(expired.messageJobId().value()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                update message_job set phase_started_at = null where message_job_id = ?
                """, UUID.fromString(expired.messageJobId().value()))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void backfills_only_allowlisted_terminal_runtime_failures_and_preserves_recovered_jobs_on_migration() {
        flyway().clean();
        Flyway.configure().dataSource(dataSource()).target("4").load().migrate();
        ConversationStore store = store();
        MessageReceipt failed = receive(store, new IncomingMessage("failed-thread", "alice", "legacy-failed", "hello"));
        MessageReceipt recovered = receive(store, new IncomingMessage("recovered-thread", "alice", "legacy-recovered", "hello"));
        MessageReceipt unknown = receive(store, new IncomingMessage("unknown-thread", "alice", "legacy-unknown", "hello"));
        seedLegacyTerminalHistory(failed, JobFailure.MODEL_UNAVAILABLE.name(), false);
        seedLegacyTerminalHistory(recovered, JobFailure.MODEL_OUTPUT_INVALID.name(), true);
        seedLegacyTerminalHistory(unknown, "UNKNOWN_LEGACY_CODE", false);
        List<SessionMessage> failedHistory = store.loadHistory(failed.sessionId());
        List<SessionMessage> recoveredHistory = store.loadHistory(recovered.sessionId());
        List<SessionMessage> unknownHistory = store.loadHistory(unknown.sessionId());

        flyway().migrate();

        assertThat(store().readJob(failed.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo(JobStatus.DONE);
            assertThat(job.progress()).isEmpty();
            assertThat(job.failure()).contains(JobFailure.MODEL_UNAVAILABLE);
        });
        assertThat(store().readJob(recovered.messageJobId()).orElseThrow().failure()).isEmpty();
        assertThat(store().readJob(unknown.messageJobId()).orElseThrow().failure()).isEmpty();
        assertThat(store().loadHistory(failed.sessionId())).isEqualTo(failedHistory);
        assertThat(store().loadHistory(recovered.sessionId())).isEqualTo(recoveredHistory);
        assertThat(store().loadHistory(unknown.sessionId())).isEqualTo(unknownHistory);
        assertThat(store().readSession(failed.sessionId()).orElseThrow().currentJob())
                .contains(store().readJob(failed.messageJobId()).orElseThrow());
    }

    private void seedLegacyTerminalHistory(MessageReceipt receipt, String runtimeCode, boolean recovered) {
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        jdbcTemplate.update("""
                with event as (
                    insert into session_message(session_id, sequence, message_job_id, role, created_at)
                    values (?, 2, ?, 'RUNTIME', clock_timestamp())
                    returning session_id, sequence)
                insert into runtime_message(session_id, sequence, code, message)
                select session_id, sequence, ?, 'legacy runtime event' from event
                """, sessionId, jobId, runtimeCode);
        jdbcTemplate.update("update conversation_session set next_sequence = next_sequence + 1 where session_id = ?",
                sessionId);
        if (recovered) {
            jdbcTemplate.update("""
                    with event as (
                        insert into session_message(session_id, sequence, message_job_id, role, created_at)
                        values (?, 3, ?, 'ASSISTANT', clock_timestamp())
                        returning session_id, sequence)
                    insert into assistant_message(session_id, sequence, message)
                    select session_id, sequence, 'successful recovery' from event
                    """, sessionId, jobId);
            jdbcTemplate.update("update conversation_session set next_sequence = next_sequence + 1 where session_id = ?",
                    sessionId);
        }
        jdbcTemplate.update("""
                update message_job set status = 'DONE', completed_at = clock_timestamp()
                where message_job_id = ?
                """, jobId);
    }

    private void assertLivePhase(MessageReceipt receipt, JobPhase phase, Optional<String> toolName) {
        ConversationStore fresh = store();
        ConversationStore.MessageJobProjection job = fresh.readJob(receipt.messageJobId()).orElseThrow();
        assertThat(job.progress()).hasValueSatisfying(progress -> {
            assertThat(progress.phase()).isEqualTo(phase);
            assertThat(progress.startedAt()).isNotNull();
            assertThat(progress.toolName()).isEqualTo(toolName);
        });
        assertThat(job.failure()).isEmpty();
        assertThat(fresh.readSession(receipt.sessionId()).orElseThrow().currentJob()).contains(job);
    }

    private static void awaitGate(CountDownLatch gate) {
        try {
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for controlled processing gate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void keeps_committed_history_visible_across_retry_and_global_job_ordering() {
        ConversationStore store = store();
        MessageReceipt first = receive(store, new IncomingMessage("thread", "alice", "first", "one"));
        MessageWorkClaim firstClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(firstClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("working")),
                ConversationStore.JobUpdate.KEEP_WORKING), NOW);
        assertThat(store.scheduleRetry(firstClaim, Duration.ZERO, JobFailure.MODEL_UNAVAILABLE)).isTrue();
        assertThat(store.loadHistory(first.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L, 2L);
        MessageWorkClaim reclaimed = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.loadHistory(first.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L);
        store.append(reclaimed, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), NOW.plusSeconds(1));
        MessageReceipt second = receive(store, new IncomingMessage("thread", "alice", "second", "two"));
        MessageWorkClaim secondClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.loadHistory(second.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L);
        store.append(secondClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("second done")),
                ConversationStore.JobUpdate.COMPLETE), NOW.plusSeconds(2));
        assertThat(store.loadHistory(second.sessionId())).extracting(message -> message.sequence().value())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void allows_only_one_concurrent_claim_and_model_reservation_for_a_session() throws Exception {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        List<Optional<MessageWorkClaim>> claims = concurrently(() -> store.claimNext(
                "worker-" + Thread.currentThread().threadId(), Duration.ofSeconds(30)));

        assertThat(claims.stream().filter(optionalClaim -> optionalClaim.isPresent())).hasSize(1);
        MessageWorkClaim claim = claims.stream().flatMap(optionalClaim -> optionalClaim.stream())
                .findFirst().orElseThrow();
        List<OptionalInt> reservations = concurrently(() -> store.reserveModelCall(claim, 1, NOW));

        assertThat(reservations).containsExactlyInAnyOrder(OptionalInt.of(1), OptionalInt.empty());
        assertThat(jdbcTemplate.queryForObject("select model_calls from message_job where message_job_id = ?", Integer.class,
                UUID.fromString(receipt.messageJobId().value()))).isEqualTo(1);
    }

    @Test
    void skips_a_locked_or_backed_off_session_head_to_another_session() throws Exception {
        ConversationStore store = store();
        MessageReceipt firstA = receive(store, new IncomingMessage("a-thread", "alice", "a-first", "one"));
        MessageReceipt secondA = receive(store, new IncomingMessage("a-thread", "alice", "a-second", "two"));
        MessageReceipt b = receive(store, new IncomingMessage("b-thread", "alice", "b", "three"));
        MessageReceipt c = receive(store, new IncomingMessage("c-thread", "alice", "c", "four"));
        UUID firstAJobId = UUID.fromString(firstA.messageJobId().value());
        jdbcTemplate.update("update message_job set available_at = clock_timestamp() - interval '10 seconds' where message_job_id = ?", firstAJobId);
        jdbcTemplate.update("update message_job set available_at = clock_timestamp() - interval '5 seconds' where message_job_id = ?",
                UUID.fromString(b.messageJobId().value()));

        try (Connection connection = dataSource().getConnection();
                PreparedStatement lock = connection.prepareStatement(
                        "select message_job_id from message_job where message_job_id = ? for update")) {
            connection.setAutoCommit(false);
            lock.setObject(1, firstAJobId);
            lock.executeQuery();

            MessageWorkClaim afterLockedHead = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

            assertThat(afterLockedHead.sessionId()).isEqualTo(b.sessionId());
            connection.rollback();
        }

        jdbcTemplate.update("update message_job set available_at = clock_timestamp() + interval '1 hour' where message_job_id = ?", firstAJobId);
        MessageWorkClaim afterBackoffHead = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        assertThat(afterBackoffHead.sessionId()).isEqualTo(c.sessionId());
        assertThat(store.readJob(secondA.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void skips_an_expired_locally_occupied_session_and_claims_another_session() throws Exception {
        ConversationStore store = store();
        MessageReceipt firstReceipt = receive(store, new IncomingMessage("thread", "alice", "first", "one"));
        MessageReceipt secondReceipt = receive(store, new IncomingMessage("other-thread", "alice", "second", "two"));
        CountDownLatch firstToolStarted = new CountDownLatch(1);
        CountDownLatch firstProcessorFinished = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTool = new CountDownLatch(1);
        CountDownLatch releaseFirstWorkflow = new CountDownLatch(1);
        AtomicInteger firstToolCalls = new AtomicInteger();
        AtomicInteger laterToolCalls = new AtomicInteger();
        AtomicReference<MessageJobProcessingResult> firstResult = new AtomicReference<>();
        AtomicReference<SessionId> secondClaimedSession = new AtomicReference<>();
        MessageJobPort processor = processor(store, firstToolStarted, releaseFirstTool, firstToolCalls, laterToolCalls);
        MessageJobPort jobs = (claim, guard) -> {
            if (claim.sessionId().equals(firstReceipt.sessionId())) {
                MessageJobProcessingResult result = processor.process(claim, guard);
                firstResult.set(result);
                firstProcessorFinished.countDown();
                try {
                    releaseFirstWorkflow.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return result;
            } else {
                secondClaimedSession.set(claim.sessionId());
                secondStarted.countDown();
            }
            return MessageJobProcessingResult.COMPLETED;
        };
        ScheduledExecutorService renewalScheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        ExecutorService invocations = Executors.newFixedThreadPool(2);
        MessageJobWorker worker = new MessageJobWorker(store, jobs,
                new WorkerProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(1), 4),
                Clock.systemUTC(), renewalScheduler, workerExecutor, "worker", new NoOpConversationTelemetry());
        try {
            Future<Boolean> first = invocations.submit(worker::poll);
            assertThat(firstToolStarted.await(5, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?",
                    UUID.fromString(firstReceipt.messageJobId().value()));

            releaseFirstTool.countDown();
            assertThat(firstProcessorFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(firstResult).hasValue(MessageJobProcessingResult.OWNERSHIP_LOST);
            assertThat(firstToolCalls).hasValue(1);
            assertThat(laterToolCalls).hasValue(0);
            assertThat(store.loadHistory(firstReceipt.sessionId())).hasSize(1);

            Future<Boolean> second = invocations.submit(worker::poll);
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondClaimedSession).hasValue(secondReceipt.sessionId());
            assertThat(store.readJob(firstReceipt.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.WORKING);

            releaseFirstWorkflow.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirstTool.countDown();
            releaseFirstWorkflow.countDown();
            invocations.shutdownNow();
            workerExecutor.shutdownNow();
            renewalScheduler.shutdownNow();
        }
    }

    private static MessageJobPort processor(
            ConversationStore store,
            CountDownLatch firstToolStarted,
            CountDownLatch releaseFirstTool,
            AtomicInteger firstToolCalls,
            AtomicInteger laterToolCalls) {
        ModelRouteId routeId = new ModelRouteId("test");
        ConversationModel model = new ConversationModel() {
            @Override
            public ModelRouteId routeId() {
                return routeId;
            }

            @Override
            public ModelCallResult respond(
                    ModelRequest request,
                    ModelCallReservation reservation,
                    Consumer<ModelUsage> usageObserver) {
                reservation.reserve();
                return new ModelCallResult(new UseTools(Optional.empty(), List.of(
                        new ToolRequest(new ToolCallId("first"), new ToolName("first"), Map.of()),
                        new ToolRequest(new ToolCallId("second"), new ToolName("second"), Map.of()))), Optional.empty());
            }
        };
        ConversationModels models = new ConversationModels() {
            @Override
            public Optional<ModelSelection> findSelection(String modelName) {
                return Optional.of(TEST_SELECTION);
            }

            @Override
            public ModelSelection defaultSelection() {
                return TEST_SELECTION;
            }

            @Override
            public Optional<ConversationModel> resolve(ModelSelection selection) {
                return Optional.of(model);
            }
        };
        ToolCatalog catalog = () -> new ToolSnapshot(List.of(
                new ToolBinding(new ToolDefinition(new ToolName("first"), "first", Map.of()), arguments -> {
                    firstToolCalls.incrementAndGet();
                    firstToolStarted.countDown();
                    try {
                        releaseFirstTool.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return new ToolOutput(false, Map.of("first", true));
                }),
                new ToolBinding(new ToolDefinition(new ToolName("second"), "second", Map.of()), arguments -> {
                    laterToolCalls.incrementAndGet();
                    return new ToolOutput(false, Map.of("second", true));
                })));
        return new JobProcessor(store, models, catalog, Clock.systemUTC(), 2,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(60)), new NoOpConversationTelemetry());
    }

    @Test
    void consumes_a_reserved_ordinal_across_reclaim_while_leaving_uncommitted_tool_work_replayable() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim original = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        assertThat(store.reserveModelCall(original, 2, NOW)).hasValue(1);
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?",
                UUID.fromString(original.messageJobId().value()));

        MessageWorkClaim recovered = store.claimNext("recovery-worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L);
        assertThat(store.reserveModelCall(recovered, 2, NOW)).hasValue(2);
    }

    @Test
    void rejects_an_aba_stale_claim_without_allocating_or_appending_a_message() {
        ConversationStore store = store();
        MessageReceipt receipt = receive(store, new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim original = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?",
                UUID.fromString(receipt.messageJobId().value()));
        MessageWorkClaim replacement = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        assertThat(replacement.claimNumber()).isGreaterThan(original.claimNumber());
        assertThatThrownBy(() -> store.append(original, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.AssistantData("stale")), ConversationStore.JobUpdate.KEEP_WORKING), NOW))
                .isExactlyInstanceOf(StaleWorkClaimException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
    }

    @Test
    void binds_one_model_route_for_the_complete_job() {
        ConversationStore store = store();
        receive(store, new IncomingMessage("thread", "alice", "route-job", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        store.bindModelRoute(claim, new ModelRouteId("gemini-primary"));
        store.bindModelRoute(claim, new ModelRouteId("gemini-primary"));

        assertThatThrownBy(() -> store.bindModelRoute(claim, new ModelRouteId("codex-primary")))
                .isExactlyInstanceOf(ModelRouteMismatchException.class);
    }

    @Test
    void rejects_direct_model_identity_changes_while_preserving_claim_retry_and_completion_transitions() {
        ConversationStore store = store();
        ModelSelection selection = new ModelSelection("google-primary", "v1:google-a");
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"), selection,
                model -> Optional.of(selection));
        UUID messageJobId = UUID.fromString(receipt.messageJobId().value());

        assertThatThrownBy(() -> jdbcTemplate.update("update message_job set model_key = ? where message_job_id = ?",
                "openai-primary", messageJobId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("update message_job set model_fingerprint = ? where message_job_id = ?",
                "v2:openai-b", messageJobId)).isInstanceOf(RuntimeException.class);

        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(store.scheduleRetry(claim, Duration.ZERO, JobFailure.MODEL_UNAVAILABLE)).isTrue();
        MessageWorkClaim retry = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(retry, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), NOW);
        assertThat(store.readJob(receipt.messageJobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void binds_the_current_session_selection_at_intake_without_rewriting_existing_or_duplicate_jobs() {
        ConversationStore store = store();
        ModelSelection first = new ModelSelection("google-primary", "v1:google-a");
        ModelSelection second = new ModelSelection("openai-primary", "v1:openai-b");

        MessageReceipt original = store.receive(new IncomingMessage("thread", "alice", "first", "one"), first,
                model -> selection(model, first, second));
        MessageWorkClaim originalClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        assertThat(originalClaim.modelSelection()).isEqualTo(first);
        assertThat(store.scheduleRetry(originalClaim, Duration.ZERO, JobFailure.MODEL_UNAVAILABLE)).isTrue();

        assertThat(store.selectModel(original.sessionId(), first)).contains(first);
        assertThat(jdbcTemplate.queryForObject("select count(*) from message_job", Integer.class)).isEqualTo(1);
        assertThat(store.selectModel(original.sessionId(), second)).contains(second);
        MessageReceipt duplicate = store.receive(new IncomingMessage("thread", "alice", "first", "one"), first,
                model -> selection(model, first, second));
        MessageReceipt afterSwitch = store.receive(new IncomingMessage("thread", "alice", "second", "two"), first,
                model -> selection(model, first, second));
        MessageReceipt otherSession = store.receive(new IncomingMessage("other-thread", "alice", "other", "three"), first,
                model -> selection(model, first, second));

        assertThat(duplicate).isEqualTo(original);
        assertThat(jobSelection(original)).isEqualTo(first);
        assertThat(jobSelection(afterSwitch)).isEqualTo(second);
        assertThat(jobSelection(otherSession)).isEqualTo(first);
    }

    @Test
    void uses_the_selection_committed_before_intake_when_intake_precedes_a_switch() {
        ConversationStore store = store();
        ModelSelection first = new ModelSelection("google-primary", "v1:google-a");
        ModelSelection second = new ModelSelection("openai-primary", "v1:openai-b");

        MessageReceipt beforeSwitch = store.receive(new IncomingMessage("thread", "alice", "first", "one"), first,
                model -> selection(model, first, second));
        assertThat(store.selectModel(beforeSwitch.sessionId(), second)).contains(second);
        MessageReceipt afterSwitch = store.receive(new IncomingMessage("thread", "alice", "second", "two"), first,
                model -> selection(model, first, second));

        assertThat(jobSelection(beforeSwitch)).isEqualTo(first);
        assertThat(jobSelection(afterSwitch)).isEqualTo(second);
    }

    @Test
    void binds_the_original_selection_when_a_waiting_intake_precedes_a_waiting_switch() throws Exception {
        ConversationStore store = store();
        ModelSelection first = new ModelSelection("google-primary", "v1:google-a");
        ModelSelection second = new ModelSelection("openai-primary", "v1:openai-b");
        MessageReceipt initial = store.receive(new IncomingMessage("thread", "alice", "initial", "one"), first,
                model -> selection(model, first, second));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection connection = dataSource().getConnection();
                PreparedStatement lock = connection.prepareStatement(
                        "select session_id from conversation_session where session_id = ? for update")) {
            connection.setAutoCommit(false);
            lock.setObject(1, UUID.fromString(initial.sessionId().value()));
            lock.executeQuery();
            Future<MessageReceipt> intakeResult = executor.submit(() -> {
                return store.receive(new IncomingMessage("thread", "alice", "concurrent", "two"), first,
                        model -> selection(model, first, second));
            });
            awaitDatabaseLockWaiter("where source_type");
            Future<Optional<ModelSelection>> switchResult = executor.submit(() -> store.selectModel(initial.sessionId(), second));
            awaitDatabaseLockWaiter("where session_id");
            connection.commit();

            MessageReceipt concurrent = intakeResult.get();
            assertThat(switchResult.get()).contains(second);
            assertThat(jobSelection(concurrent)).isEqualTo(first);
            assertThat(jdbcTemplate.queryForObject("select selected_model from conversation_session where session_id = ?", String.class,
                    UUID.fromString(initial.sessionId().value()))).isEqualTo(second.model());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void binds_the_new_selection_when_a_waiting_switch_precedes_a_waiting_intake() throws Exception {
        ConversationStore store = store();
        ModelSelection first = new ModelSelection("google-primary", "v1:google-a");
        ModelSelection second = new ModelSelection("openai-primary", "v1:openai-b");
        MessageReceipt initial = store.receive(new IncomingMessage("thread", "alice", "initial", "one"), first,
                model -> selection(model, first, second));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection connection = dataSource().getConnection();
                PreparedStatement lock = connection.prepareStatement(
                        "select session_id from conversation_session where session_id = ? for update")) {
            connection.setAutoCommit(false);
            lock.setObject(1, UUID.fromString(initial.sessionId().value()));
            lock.executeQuery();
            Future<Optional<ModelSelection>> switchResult = executor.submit(() -> store.selectModel(initial.sessionId(), second));
            awaitDatabaseLockWaiter("where session_id");
            Future<MessageReceipt> intakeResult = executor.submit(() -> store.receive(
                    new IncomingMessage("thread", "alice", "concurrent", "two"), first, model -> selection(model, first, second)));
            awaitDatabaseLockWaiter("where source_type");
            connection.commit();

            assertThat(switchResult.get()).contains(second);
            assertThat(jobSelection(intakeResult.get())).isEqualTo(second);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void keeps_exact_usage_checkpoints_separate_when_only_the_configured_fingerprint_changes() {
        ConversationStore store = store();
        ModelSelection original = new ModelSelection("openai-primary", "v1:configuration-a");
        ModelSelection changed = new ModelSelection("openai-primary", "v2:configuration-b");
        ModelDescriptor descriptor = new ModelDescriptor(new ModelRouteId("openai-primary"), "gpt-5-mini", 32_000);
        String shape = "a".repeat(64);
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"), original,
                model -> Optional.of(original));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        store.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE, Optional.empty(), Optional.of(
                        new ConversationStore.UsageCheckpointData(original, descriptor, 1, 20, 10, 30, shape, 0))), NOW);

        assertThat(store.loadUsageCheckpoint(receipt.sessionId(), original, descriptor, shape, 0)).isPresent();
        assertThat(store.loadUsageCheckpoint(receipt.sessionId(), changed, descriptor, shape, 0)).isEmpty();
    }

    private ModelSelection jobSelection(MessageReceipt receipt) {
        return jdbcTemplate.queryForObject("select model_key, model_fingerprint from message_job where message_job_id = ?",
                (resultSet, row) -> new ModelSelection(resultSet.getString("model_key"), resultSet.getString("model_fingerprint")),
                UUID.fromString(receipt.messageJobId().value()));
    }

    private static MessageReceipt receive(ConversationStore store, IncomingMessage incomingMessage) {
        return store.receive(incomingMessage, TEST_SELECTION, model -> Optional.of(TEST_SELECTION));
    }

    private static java.util.Optional<ModelSelection> selection(
            String model,
            ModelSelection first,
            ModelSelection second) {
        if (first.model().equals(model)) {
            return java.util.Optional.of(first);
        }
        if (second.model().equals(model)) {
            return java.util.Optional.of(second);
        }
        return java.util.Optional.empty();
    }

    private <T> List<T> concurrently(Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<T> synchronizedAction = () -> {
                ready.countDown();
                start.await();
                return action.call();
            };
            Future<T> first = executor.submit(synchronizedAction);
            Future<T> second = executor.submit(synchronizedAction);
            ready.await();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitDatabaseLockWaiter(String queryFragment) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                    select count(*)
                    from pg_stat_activity
                    where datname = current_database()
                      and wait_event_type = 'Lock'
                      and query like ?
                    """, Integer.class, "%" + queryFragment + "%"), "PostgreSQL count must not be null");
            if (waiting > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Timed out waiting for the controlled PostgreSQL row-lock boundary");
    }

    private ConversationStore store() {
        return new PostgresConversationStore(dataSource(), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
