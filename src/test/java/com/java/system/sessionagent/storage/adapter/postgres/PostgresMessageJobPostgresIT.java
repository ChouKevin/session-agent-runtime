package com.java.system.sessionagent.storage.adapter.postgres;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
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
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.port.in.MessageJobPort;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
    void keeps_committed_history_visible_across_retry_and_global_job_ordering() {
        ConversationStore store = store();
        MessageReceipt first = receive(store, new IncomingMessage("thread", "alice", "first", "one"));
        MessageWorkClaim firstClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(firstClaim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("working")),
                ConversationStore.JobUpdate.KEEP_WORKING), NOW);
        assertThat(store.scheduleRetry(firstClaim, Duration.ZERO)).isTrue();
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
        assertThat(store.scheduleRetry(claim, Duration.ZERO)).isTrue();
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
        assertThat(store.scheduleRetry(originalClaim, Duration.ZERO)).isTrue();

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
