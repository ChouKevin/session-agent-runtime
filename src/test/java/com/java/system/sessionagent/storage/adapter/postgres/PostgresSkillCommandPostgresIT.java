package com.java.system.sessionagent.storage.adapter.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
import com.java.system.sessionagent.conversation.domain.SkillCommandState;
import com.java.system.sessionagent.conversation.domain.SkillMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.storage.adapter.postgres.jdbc.PostgresIntakeCapacity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

class PostgresSkillCommandPostgresIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final ModelSelection SELECTION = new ModelSelection("test", "v1:test");
    private static final SkillResource RESOURCE = new SkillResource("skill://review/SKILL.md", "a".repeat(64), "# Current bytes\nReview carefully.");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ConversationStore store;

    @BeforeEach
    void fresh_schema() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway().clean();
        flyway().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = reconstructedStore();
    }

    @AfterEach
    void clean_schema() { flyway().clean(); }

    @Test
    void intake_and_source_replay_retain_pending_classification_and_exact_user_audit() {
        String text = "/skill:../bad  question\nwith spacing  ";
        MessageReceipt receipt = receive("source", text);
        ConversationHistory history = store.loadContextHistory(receipt.sessionId());
        assertThat(history.commands().get(receipt.messageJobId()).disposition()).isEqualTo(SkillCommandState.Disposition.PENDING);
        assertThat(history.commands().get(receipt.messageJobId()).resolutionSequence()).isEmpty();
        assertThat(history.messages()).singleElement().isInstanceOfSatisfying(UserMessage.class,
                user -> assertThat(user.message()).isEqualTo(text));
        assertThat(store.receive(new IncomingMessage("thread", "alice", "source", text), SELECTION,
                model -> { throw new AssertionError("Replay must precede model lookup"); })).isEqualTo(receipt);
        assertThat(store.loadContextHistory(receipt.sessionId())).isEqualTo(history);
        assertThat(count("skill_command")).isEqualTo(1);
    }

    @Test
    void committed_load_round_trips_after_reconstruction_and_replays_without_appending_or_replacing_bytes() {
        MessageReceipt receipt = receive("source", "/skill:review explain");
        MessageWorkClaim claim = claim();
        assertThat(store.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now())).isEqualTo(JobStatus.WORKING);
        ConversationStore restarted = reconstructedStore();
        ConversationHistory history = restarted.loadContextHistory(receipt.sessionId());
        assertThat(history.commands().get(receipt.messageJobId()).disposition()).isEqualTo(SkillCommandState.Disposition.ACCEPTED);
        assertThat(history.commands().get(receipt.messageJobId()).resolutionSequence()).contains(history.messages().get(1).sequence());
        assertThat(history.messages().get(1)).isInstanceOfSatisfying(SkillMessage.class,
                skill -> assertThat(skill.resource()).isEqualTo(RESOURCE));
        assertThat(restarted.loadHistory(receipt.sessionId())).isEqualTo(history.messages());
        assertThat(restarted.loadHistoryPage(receipt.sessionId(), 1, 1)).contains(List.of(history.messages().get(1)));
        assertThat(restarted.resolveSkillCommand(claim, new SkillReadResult.Failure("changed"), Instant.now())).isEqualTo(JobStatus.WORKING);
        assertThat(receive("source", "/skill:review explain")).isEqualTo(receipt);
        assertThat(count("skill_message")).isEqualTo(1);
        assertThat(restarted.loadContextHistory(receipt.sessionId())).isEqualTo(history);
        restarted.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")), ConversationStore.JobUpdate.COMPLETE), Instant.now());
        assertThat(restarted.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now())).isEqualTo(JobStatus.DONE);
        assertThat(count("skill_message")).isEqualTo(1);
    }

    @Test
    void rejection_retains_safe_audit_and_never_accepts_the_selection() {
        MessageReceipt receipt = receive("source", "/skill:missing question");
        MessageWorkClaim claim = claim();
        assertThat(store.resolveSkillCommand(claim, new SkillReadResult.Failure("host-secret"), Instant.now())).isEqualTo(JobStatus.DONE);
        ConversationHistory history = reconstructedStore().loadContextHistory(receipt.sessionId());
        assertThat(history.commands().get(receipt.messageJobId()).disposition()).isEqualTo(SkillCommandState.Disposition.REJECTED);
        assertThat(history.messages().get(1)).isInstanceOfSatisfying(RuntimeMessage.class, error -> {
            assertThat(error.code()).isEqualTo(JobFailure.SKILL_UNAVAILABLE.name());
            assertThat(error.message()).isEqualTo(JobFailure.SKILL_UNAVAILABLE.message());
        });
        assertThat(store.readJob(receipt.messageJobId()).orElseThrow().failure()).contains(JobFailure.SKILL_UNAVAILABLE);
        assertThat(store.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now())).isEqualTo(JobStatus.DONE);
        assertThat(count("skill_message")).isZero();
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(2);
    }

    @Test
    void a_failure_between_detail_and_disposition_rolls_back_event_detail_and_sequence() {
        MessageReceipt receipt = receive("source", "/skill:review question");
        MessageWorkClaim claim = claim();
        jdbc.execute("""
                create function fail_skill_resolution() returns trigger language plpgsql as $$
                begin raise exception 'injected resolution failure'; end; $$;
                create trigger injected_failure before update on skill_command for each row execute function fail_skill_resolution();
                """);
        assertThatThrownBy(() -> store.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now()))
                .isInstanceOf(ConversationStoreFailure.class);
        assertThat(count("skill_message")).isZero();
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(store.loadContextHistory(receipt.sessionId()).commands().get(receipt.messageJobId()).disposition())
                .isEqualTo(SkillCommandState.Disposition.PENDING);
        jdbc.execute("drop trigger injected_failure on skill_command");
        store.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now());
        assertThat(store.loadHistory(receipt.sessionId()).get(1).sequence().value()).isEqualTo(2);
    }

    @Test
    void cancellation_and_stale_claims_cannot_publish_success() {
        MessageReceipt receipt = receive("source", "/skill:review question");
        MessageWorkClaim claim = claim();
        store.requestCancellation(receipt.messageJobId());
        assertThatThrownBy(() -> store.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now()))
                .isInstanceOf(JobCancellationRequestedException.class);
        assertThat(count("skill_message")).isZero();
        assertThat(store.loadContextHistory(receipt.sessionId()).commands().get(receipt.messageJobId()).disposition())
                .isEqualTo(SkillCommandState.Disposition.PENDING);
        jdbc.update("update message_job set locked_until = clock_timestamp() - interval '1 second' where message_job_id = ?", UUID.fromString(receipt.messageJobId().value()));
        MessageWorkClaim replacement = claim();
        assertThat(replacement.claimNumber()).isGreaterThan(claim.claimNumber());
        assertThatThrownBy(() -> store.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now()))
                .isInstanceOf(StaleWorkClaimException.class);
        assertThat(count("skill_message")).isZero();
    }

    @Test
    void queued_invalid_command_stays_out_of_durable_compaction_before_and_after_rejection() {
        MessageReceipt active = receive("active", "continue the active job");
        MessageWorkClaim activeClaim = claim();
        MessageReceipt invalid = receive("invalid", "/skill:../bad QUEUED_INVALID_PRIVATE");
        store.append(activeClaim, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.AssistantData("x".repeat(40_000))),
                ConversationStore.JobUpdate.KEEP_WORKING), Instant.now());
        List<List<SessionMessage>> summaries = new ArrayList<>();
        List<List<SessionMessage>> responses = new ArrayList<>();
        ConversationModel model = new ConversationModel() {
            @Override public ModelRouteId routeId() { return new ModelRouteId("test"); }
            @Override public ModelDescriptor descriptor() { return new ModelDescriptor(routeId(), "bounded", 12_000); }
            @Override public String summarize(ContextCompactionRequest request, ModelCallReservation reservation) {
                reservation.reserve();
                summaries.add(request.history());
                return "safe summary";
            }
            @Override public ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usage) {
                reservation.reserve();
                responses.add(request.history());
                return new ModelCallResult(new ModelReply.Text("done"), Optional.empty());
            }
        };
        try (FileSystemSkillLibrary library = new FileSystemSkillLibrary(new SkillProperties())) {
            JobProcessor processor = new JobProcessor(store, TestConfiguredModels.withModel(model),
                    () -> new ToolSnapshot(List.of()), library, Clock.systemUTC(), 4,
                    new MessageJobRetryPolicy(3, Duration.ofSeconds(1)), new NoOpConversationTelemetry());
            processor.process(activeClaim, () -> true);
            assertThat(store.loadCompaction(active.sessionId())).hasValueSatisfying(compaction ->
                    assertThat(compaction.coveredThrough().value()).isEqualTo(3));
            assertThat(store.loadContextHistory(active.sessionId()).commands().get(invalid.messageJobId()).disposition())
                    .isEqualTo(SkillCommandState.Disposition.PENDING);
            processor.process(claim(), () -> true);
            receive("following", "ordinary continuation");
            processor.process(claim(), () -> true);
        }
        assertThat(summaries).hasSize(1);
        assertThat(responses).hasSize(2);
        assertThat(summaries).allSatisfy(history -> assertThat(history).noneMatch(message ->
                message.messageJobId().filter(invalid.messageJobId()::equals).isPresent()));
        assertThat(responses).allSatisfy(history -> assertThat(history).noneMatch(message ->
                message.messageJobId().filter(invalid.messageJobId()::equals).isPresent()));
        assertThat(store.readJob(invalid.messageJobId()).orElseThrow()).satisfies(job -> {
            assertThat(job.failure()).contains(JobFailure.SKILL_UNAVAILABLE);
            assertThat(job.modelCallCount()).isZero();
        });
        assertThat(store.loadHistory(active.sessionId())).anySatisfy(message ->
                assertThat(message).isInstanceOfSatisfying(UserMessage.class,
                        user -> assertThat(user.message()).contains("QUEUED_INVALID_PRIVATE")));
    }

    @Test
    void accepted_resolution_replay_still_requires_a_live_uncancelled_claim() {
        MessageReceipt receipt = receive("source", "/skill:review question");
        MessageWorkClaim original = claim();
        store.resolveSkillCommand(original, new SkillReadResult.Success(RESOURCE), Instant.now());
        ConversationHistory accepted = store.loadContextHistory(receipt.sessionId());
        jdbc.update("update message_job set locked_until = clock_timestamp() - interval '1 second' where message_job_id = ?",
                UUID.fromString(receipt.messageJobId().value()));
        MessageWorkClaim replacement = claim();
        assertThatThrownBy(() -> store.resolveSkillCommand(original, new SkillReadResult.Failure("changed"), Instant.now()))
                .isInstanceOf(StaleWorkClaimException.class);
        store.requestCancellation(receipt.messageJobId());
        assertThatThrownBy(() -> store.resolveSkillCommand(replacement, new SkillReadResult.Failure("changed"), Instant.now()))
                .isInstanceOf(JobCancellationRequestedException.class);
        assertThat(store.loadContextHistory(receipt.sessionId())).isEqualTo(accepted);
        assertThat(count("skill_message")).isEqualTo(1);
    }

    @Test
    void lease_loss_after_writing_detail_rolls_back_the_entire_resolution() {
        MessageReceipt receipt = receive("source", "/skill:review question");
        MessageWorkClaim claim = claim();
        PostgresConversationMapper real = PostgresMybatisMapperFactory.create(dataSource);
        PostgresConversationMapper intercept = mock(PostgresConversationMapper.class, withSettings().defaultAnswer(delegatesTo(real)));
        doAnswer(invocation -> {
            int inserted = real.insertSkillMessage(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4));
            jdbc.update("update message_job set locked_until = clock_timestamp() - interval '1 second' where message_job_id = ?",
                    UUID.fromString(receipt.messageJobId().value()));
            return inserted;
        }).when(intercept).insertSkillMessage(UUID.fromString(receipt.sessionId().value()), 2, RESOURCE.uri(), RESOURCE.sha256(), RESOURCE.content());
        ConversationStore losingLease = new PostgresConversationStore(intercept, dataSource, Clock.systemUTC(),
                new ObjectMapper(), new PostgresIntakeCapacity(dataSource));
        assertThatThrownBy(() -> losingLease.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now()))
                .isInstanceOf(StaleWorkClaimException.class);
        assertThat(count("skill_message")).isZero();
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(store.loadContextHistory(receipt.sessionId()).commands().get(receipt.messageJobId()).disposition())
                .isEqualTo(SkillCommandState.Disposition.PENDING);
    }

    @Test
    void history_and_disposition_share_one_repeatable_read_snapshot_during_resolution() throws Exception {
        MessageReceipt receipt = receive("source", "/skill:review question");
        MessageWorkClaim claim = claim();
        PostgresConversationMapper real = PostgresMybatisMapperFactory.create(dataSource);
        PostgresConversationMapper intercept = mock(PostgresConversationMapper.class, withSettings().defaultAnswer(delegatesTo(real)));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            doAnswer(invocation -> {
                executor.submit(() -> store.resolveSkillCommand(claim, new SkillReadResult.Success(RESOURCE), Instant.now())).get(10, TimeUnit.SECONDS);
                return real.loadSkillCommands(invocation.getArgument(0));
            }).when(intercept).loadSkillCommands(UUID.fromString(receipt.sessionId().value()));
            ConversationStore reader = new PostgresConversationStore(intercept, dataSource, Clock.systemUTC(), new ObjectMapper(), new PostgresIntakeCapacity(dataSource));
            ConversationHistory snapshot = reader.loadContextHistory(receipt.sessionId());
            assertThat(snapshot.messages()).hasSize(1);
            assertThat(snapshot.commands().get(receipt.messageJobId()).disposition()).isEqualTo(SkillCommandState.Disposition.PENDING);
        }
        assertThat(store.loadContextHistory(receipt.sessionId()).messages()).hasSize(2);
    }

    @Test
    void database_prevents_identity_rewrites_resolved_transitions_and_append_only_detail_changes() {
        MessageReceipt receipt = receive("source", "/skill:review question");
        store.resolveSkillCommand(claim(), new SkillReadResult.Success(RESOURCE), Instant.now());
        assertThatThrownBy(() -> jdbc.update("update skill_command set disposition = 'REJECTED'" )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update skill_command set requested_name = 'other'" )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update skill_message set content = 'rewritten'" )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("delete from skill_command" )).isInstanceOf(RuntimeException.class);
        assertThat(store.loadContextHistory(receipt.sessionId()).commands().get(receipt.messageJobId()).disposition())
                .isEqualTo(SkillCommandState.Disposition.ACCEPTED);
    }

    @Test
    void database_requires_matching_role_and_same_job_resolution_events() {
        MessageReceipt receipt = receive("explicit", "/skill:review question");
        MessageWorkClaim first = claim();
        store.append(first, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("audit")),
                ConversationStore.JobUpdate.COMPLETE), Instant.now());
        MessageReceipt other = receive("ordinary", "ordinary");
        MessageWorkClaim second = claim();
        assertThat(second.messageJobId()).isEqualTo(other.messageJobId());
        store.append(second, new ConversationStore.MessageBatch(List.of(new ConversationStore.RuntimeData(
                JobFailure.SKILL_UNAVAILABLE.name(), JobFailure.SKILL_UNAVAILABLE.message())),
                ConversationStore.JobUpdate.COMPLETE), Instant.now());
        assertThatThrownBy(() -> jdbc.update("update skill_command set disposition = 'ACCEPTED', resolution_sequence = 1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update skill_command set disposition = 'REJECTED', resolution_sequence = 4"))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.loadContextHistory(receipt.sessionId()).commands().get(receipt.messageJobId()).disposition())
                .isEqualTo(SkillCommandState.Disposition.PENDING);
    }

    @Test
    void generic_append_rejects_mixed_skill_resolution_batches() {
        MessageReceipt receipt = receive("source", "/skill:review question");
        MessageWorkClaim claim = claim();
        assertThatThrownBy(() -> store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.SkillData(RESOURCE), new ConversationStore.AssistantData("mixed")), ConversationStore.JobUpdate.KEEP_WORKING), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.loadHistory(receipt.sessionId())).hasSize(1);
    }

    private MessageReceipt receive(String source, String text) {
        return store.receive(new IncomingMessage("thread", "alice", source, text), SELECTION, model -> Optional.of(SELECTION));
    }
    private MessageWorkClaim claim() { return store.claimNext("worker", Duration.ofSeconds(60)).orElseThrow(); }
    private ConversationStore reconstructedStore() { return new PostgresConversationStore(dataSource, Clock.systemUTC(), new ObjectMapper()); }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private Flyway flyway() { return Flyway.configure().dataSource(dataSource).cleanDisabled(false).load(); }
}
