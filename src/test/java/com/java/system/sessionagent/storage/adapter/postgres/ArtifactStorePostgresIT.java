package com.java.system.sessionagent.storage.adapter.postgres;

import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.domain.IntentRequest;
import com.java.system.sessionagent.conversation.domain.IntentResult;

import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.WaitingChoice;
import com.java.system.sessionagent.conversation.domain.FollowUpIntent;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import com.java.system.sessionagent.tool.TestToolInvocation;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.artifact.application.ArtifactEncoder;
import com.java.system.sessionagent.artifact.application.ArtifactToolCatalog;
import com.java.system.sessionagent.artifact.port.ArtifactStore;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobFailure;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.JobCancellationRequestedException;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.slack.adapter.postgres.SlackAttachmentRoute;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStorePostgresIT {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final ModelSelection MODEL = new ModelSelection("test", "v1:test");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ConversationStore conversations;
    private PostgresArtifactStore artifacts;
    private final Map<String, Map<String, Object>> sourceInputs = new HashMap<>();

    @Test
    void lease_expiry_during_staging_rolls_back_the_artifact() {
        MessageWorkClaim claim = claim("expiry-during-stage");
        jdbc.execute("""
                create function expire_artifact_claim() returns trigger language plpgsql as $$
                begin
                    update message_job set locked_until=clock_timestamp()-interval '1 millisecond'
                    where message_job_id=new.message_job_id;
                    return new;
                end;
                $$
                """);
        jdbc.execute("create trigger expire_artifact_claim after insert on generated_artifact for each row execute function expire_artifact_claim()");
        assertThatThrownBy(() -> save(claim, 1, 0, "expires")).isInstanceOf(StaleWorkClaimException.class);
        assertThat(count("select count(*) from generated_artifact")).isZero();
    }

    @Test
    void exact_success_receipt_cannot_be_attached_to_different_original_arguments_or_ordinal() {
        MessageWorkClaim claim = claim("source-association");
        ToolOutput saved = save(claim, 1, 0, "actual source");
        sourceInputs.put(saved.artifactReceipt().orElseThrow().artifactId().toString(), Map.of("title", "Report", "content", "changed source"));
        assertThatThrownBy(() -> append(claim, saved)).isInstanceOf(RuntimeException.class);
        ToolOutput wrongOrdinal = save(claim, 1, 1, "other ordinal");
        assertThatThrownBy(() -> append(claim, wrongOrdinal)).isInstanceOf(RuntimeException.class);
        assertThat(count("select count(*) from assistant_tool_calls")).isZero();
        assertThat(count("select count(*) from generated_artifact where observation_sequence is not null")).isZero();
    }

    @Test
    void csv_replay_rejects_different_source_even_when_spreadsheet_safe_bytes_are_identical() {
        MessageWorkClaim claim = claim("csv-source-collision");
        Map<String, Object> first = Map.of("title", "CSV", "columns", List.of("a"), "rows", List.of(List.of("=1")));
        Map<String, Object> second = Map.of("title", "CSV", "columns", List.of("a"), "rows", List.of(List.of("'=1")));
        ArtifactEncoder.Encoded encoded = ArtifactEncoder.csv(first);
        assertThat(ArtifactEncoder.csv(second).content()).isEqualTo(encoded.content());
        artifacts.create(TestToolInvocation.of(claim, 1, 0), encoded.title(), encoded.extension(), encoded.mediaType(),
                encoded.content(), "createCsv", first, encoded.disclosures());
        assertThatThrownBy(() -> artifacts.create(TestToolInvocation.of(claim, 1, 0), encoded.title(), encoded.extension(), encoded.mediaType(),
                encoded.content(), "createCsv", second, encoded.disclosures())).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("select count(*) from generated_artifact")).isEqualTo(1);
    }

    @Test
    void real_runtime_commits_export_observations_and_original_csv_inputs_before_releasing_final_answer() {
        conversations.receive(new IncomingMessage("runtime", "alice", "runtime", "report and CSV"), MODEL, model -> Optional.of(MODEL));
        MessageWorkClaim claim = conversations.claimNext("runtime-worker", Duration.ofMinutes(1)).orElseThrow();
        Map<String, Object> markdown = Map.of("title", "報告", "content", "# 原文😀\r\n```\nexact\n```");
        Map<String, Object> csv = Map.of("title", "資料", "columns", List.of("value"), "rows", List.of(List.of("=1+1")));
        AtomicInteger requests = new AtomicInteger();
        ConversationModel model =
                new ConversationModel() {
                    @Override public IntentResult classifyIntent(IntentRequest request, ModelCallReservation reservation, Consumer<ModelCallObservation> observationObserver) {
                        throw new AssertionError("Unexpected intent request in ordinary-job fixture");
                    }
                    @Override
                    public ModelRouteId routeId() {
                        return new ModelRouteId("test");
                    }
                    @Override
                    public ModelCallResult respond(
                            ModelRequest request,
                            ModelCallReservation reservation,
                            Consumer<ModelCallObservation> usage) {
                        reservation.reserve();
                        ModelReply reply = requests.getAndIncrement() == 0
                                ? new ModelReply.UseTools(Optional.of("Saving"), List.of(
                                        new ToolRequest(new ToolCallId("md"), new ToolName("createMarkdownReport"), markdown),
                                        new ToolRequest(new ToolCallId("csv"), new ToolName("createCsv"), csv)))
                                : new ModelReply.Text("final reply unchanged");
                        return new ModelCallResult(reply, Optional.empty());
                    }
                };
        ArtifactToolCatalog catalog = new ArtifactToolCatalog(() -> new ToolSnapshot(List.of()), artifacts, (session, job) -> true);
        JobProcessor processor =
                new JobProcessor(conversations,
                        TestConfiguredModels.withModel(model), catalog,
                        new FileSystemSkillLibrary(
                                new SkillProperties()),
                        Clock.systemUTC(), 3,
                        new MessageJobRetryPolicy(3, Duration.ofSeconds(1)),
                        new NoOpConversationTelemetry());
        assertThat(processor.process(claim, () -> true))
                .isEqualTo(MessageJobProcessingResult.COMPLETED);
        assertThat(released(claim)).hasSize(2);
        assertThat(released(claim).getFirst().content()).isEqualTo(((String) markdown.get("content")).getBytes(StandardCharsets.UTF_8));
        assertThat(new String(released(claim).getLast().content(), StandardCharsets.UTF_8)).isEqualTo("\ufeff\"value\"\r\n\"'=1+1\"\r\n");
        assertThat(jdbc.queryForObject("select calls->1->'arguments'->'rows'->0->>0 from assistant_tool_calls", String.class)).isEqualTo("=1+1");
        assertThat(jdbc.queryForObject("select message from assistant_message", String.class)).isEqualTo("final reply unchanged");
        assertThat(jdbc.queryForObject("select model_calls from message_job", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select role from session_message order by sequence", String.class))
                .containsExactly("USER", "ASSISTANT_TOOL_CALLS", "TOOL", "TOOL", "ASSISTANT");
    }

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway().clean();
        flyway().migrate();
        jdbc = new JdbcTemplate(dataSource);
        conversations = new PostgresConversationStore(dataSource, Clock.systemUTC(), new ObjectMapper());
        artifacts = new PostgresArtifactStore(dataSource, new ObjectMapper());
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void actual_observation_commit_is_atomic_and_final_success_releases_only_committed_outputs() {
        MessageWorkClaim claim = claim("atomic");
        ToolOutput saved = save(claim, 1, 0, "body");
        assertThat(released(claim)).isEmpty();
        jdbc.execute("""
                create function reject_tool_observation() returns trigger language plpgsql as $$
                begin raise exception 'injected observation failure'; end;
                $$
                """);
        jdbc.execute("create constraint trigger reject_tool_observation after insert on tool_observation deferrable initially deferred for each row execute function reject_tool_observation()");
        assertThatThrownBy(() -> append(claim, saved)).isInstanceOf(RuntimeException.class);
        assertThat(count("select count(*) from assistant_tool_calls")).isZero();
        assertThat(count("select count(*) from generated_artifact where observation_sequence is not null")).isZero();
        assertThat(released(claim)).isEmpty();
        jdbc.execute("drop trigger reject_tool_observation on tool_observation");
        jdbc.execute("drop function reject_tool_observation()");
        append(claim, saved);
        assertThat(count("select count(*) from generated_artifact where observation_sequence is not null")).isEqualTo(1);
        assertThat(released(claim)).isEmpty();
        finish(claim);
        assertThat(released(claim)).singleElement().satisfies(artifact -> {
            assertThat(artifact.content()).isEqualTo("body".getBytes(StandardCharsets.UTF_8));
            assertThat(artifact.filename()).isEqualTo(artifact.artifactId() + ".md");
        });
        assertThat(jdbc.queryForList("select role from session_message order by sequence", String.class))
                .containsExactly("USER", "ASSISTANT_TOOL_CALLS", "TOOL", "ASSISTANT");
    }

    @Test
    void forged_model_visible_lookalikes_and_different_success_observations_do_not_publish_artifacts() {
        MessageWorkClaim claim = claim("lookalike");
        ToolOutput staged = save(claim, 1, 0, "orphan");
        ToolOutput forged = new ToolOutput(false, staged.result());
        append(claim, forged);
        finish(claim);
        assertThat(released(claim)).isEmpty();
        assertThat(count("select count(*) from generated_artifact where observation_sequence is not null")).isZero();
    }

    @Test
    void wrong_observation_with_real_receipt_rolls_back_the_entire_batch() {
        MessageWorkClaim claim = claim("wrong-observation");
        ToolOutput staged = save(claim, 1, 0, "body");
        ToolOutput changed = new ToolOutput(false, Map.of("status", "not the actual output"), staged.artifactReceipt());
        assertThatThrownBy(() -> append(claim, changed)).isInstanceOf(RuntimeException.class);
        assertThat(count("select count(*) from assistant_tool_calls")).isZero();
        assertThat(count("select count(*) from tool_observation")).isZero();
        assertThat(count("select count(*) from generated_artifact where observation_sequence is not null")).isZero();
    }

    @Test
    void restart_replays_same_invocation_but_rejects_changed_content_and_keeps_old_orphans_invisible() {
        MessageWorkClaim first = claim("restart");
        ToolOutput orphan = save(first, 1, 0, "old");
        artifacts = new PostgresArtifactStore(dataSource, new ObjectMapper());
        assertThat(save(first, 1, 0, "old")).isEqualTo(orphan);
        assertThatThrownBy(() -> save(first, 1, 0, "changed")).isInstanceOf(IllegalArgumentException.class);
        expire(first);
        MessageWorkClaim restarted = conversations.claimNext("restarted", Duration.ofMinutes(1)).orElseThrow();
        assertThat(save(restarted, 1, 0, "old")).isEqualTo(orphan);
        int call = ((ModelCallReservationResult.Reserved) conversations.reserveModelCall(restarted, ModelCallPurpose.EXECUTION, 30, Instant.now())).totalOrdinal();
        ToolOutput actual = save(restarted, call, 0, "new");
        append(restarted, actual);
        finish(restarted);
        assertThat(released(restarted)).singleElement().satisfies(artifact ->
                assertThat(artifact.content()).isEqualTo("new".getBytes(StandardCharsets.UTF_8)));
        assertThat(count("select count(*) from generated_artifact where observation_sequence is null and not released")).isEqualTo(1);
    }

    @Test
    void stale_cancelled_and_uncounted_invocations_cannot_create_or_commit() {
        MessageWorkClaim stale = claim("stale");
        ToolOutput pending = save(stale, 1, 0, "pending");
        expire(stale);
        assertThatThrownBy(() -> save(stale, 1, 1, "late")).isInstanceOf(StaleWorkClaimException.class);
        assertThatThrownBy(() -> append(stale, pending)).isInstanceOf(StaleWorkClaimException.class);
        MessageWorkClaim replacement = conversations.claimNext("replacement", Duration.ofMinutes(1)).orElseThrow();
        assertThatThrownBy(() -> save(replacement, 2, 0, "not counted")).isInstanceOf(IllegalArgumentException.class);
        conversations.requestCancellation(replacement.messageJobId());
        assertThatThrownBy(() -> save(replacement, 1, 1, "cancelled")).isInstanceOf(JobCancellationRequestedException.class);
        append(replacement, pending);
        assertThat(released(replacement)).isEmpty();
        assertThat(count("select count(*) from generated_artifact")).isEqualTo(1);
    }

    @Test
    void terminal_failure_retains_committed_audit_without_releasing_it() {
        MessageWorkClaim claim = claim("failed");
        append(claim, save(claim, 1, 0, "audit"));
        conversations.append(claim, new ConversationStore.MessageBatch(
                List.of(new ConversationStore.RuntimeData("MODEL_UNAVAILABLE", "unavailable")), ConversationStore.JobUpdate.COMPLETE,
                Optional.empty(), Optional.empty(), Optional.of(JobFailure.MODEL_UNAVAILABLE), ConversationStore.CompletionDisposition.NONE), Instant.now());
        assertThat(released(claim)).isEmpty();
        assertThat(count("select count(*) from generated_artifact where observation_sequence is not null")).isEqualTo(1);
    }

    @Test
    void job_ownership_isolation_rejects_cross_job_receipts_and_hides_bytes() {
        MessageWorkClaim first = claim("one");
        ToolOutput saved = save(first, 1, 0, "private");
        MessageWorkClaim second = claim("two");
        assertThatThrownBy(() -> append(second, saved)).isInstanceOf(RuntimeException.class);
        append(first, saved);
        finish(first);
        assertThat(artifacts.released(UUID.fromString(second.sessionId().value()), UUID.fromString(first.messageJobId().value()))).isEmpty();
        assertThat(released(second)).isEmpty();
        assertThat(released(first)).hasSize(1);
    }

    @Test
    void enforces_per_job_count_and_byte_limits_including_staged_artifacts_without_charging_replay_twice() {
        MessageWorkClaim countLimited = claim("count");
        for (int index = 0; index < 10; index++) {
            save(countLimited, 1, index, "small");
        }
        save(countLimited, 1, 0, "small");
        assertThatThrownBy(() -> save(countLimited, 1, 10, "eleventh")).isInstanceOf(IllegalArgumentException.class);
        MessageWorkClaim byteLimited = claim("bytes");
        String megabyte = "x".repeat(1_048_576);
        for (int index = 0; index < 5; index++) {
            save(byteLimited, 1, index, megabyte);
        }
        assertThatThrownBy(() -> save(byteLimited, 1, 5, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("select count(*) from generated_artifact")).isEqualTo(15);
    }

    @Test
    void malformed_tool_input_never_persists_a_staged_artifact() {
        MessageWorkClaim claim = claim("invalid");
        ArtifactToolCatalog catalog = new ArtifactToolCatalog(() -> new ToolSnapshot(List.of()), artifacts, (session, job) -> true);
        try (ToolSnapshot tools = catalog.snapshot(claim.sessionId().value(), claim.messageJobId().value())) {
            ToolOutput output = tools.invoke(TestToolInvocation.of(claim, 1, 0), new ToolName("createCsv"),
                    Map.of("title", "bad", "columns", List.of("a"), "rows", List.of(List.of(42))));
            assertThat(output.isError()).isTrue();
            assertThat(count("select count(*) from generated_artifact")).isZero();
        }
    }

    @Test
    void configured_attachment_route_requires_actual_job_receipt_and_original_thread_binding() {
        MessageWorkClaim claim = claim("route");
        UUID session = UUID.fromString(claim.sessionId().value());
        UUID job = UUID.fromString(claim.messageJobId().value());
        SlackProperties configured = new SlackProperties("app", "bot", "user", Duration.ofSeconds(1), Duration.ofSeconds(1));
        SlackAttachmentRoute route = new SlackAttachmentRoute(dataSource, configured);
        assertThat(route.eligible(session, job)).isFalse();
        jdbc.update("insert into slack_thread_binding(team_id,channel_id,root_thread_ts,session_id,created_at) values ('T','C','1',?,clock_timestamp())", session);
        jdbc.update("""
                insert into slack_message_receipt(team_id,channel_id,message_ts,top_level,classification,session_id,message_job_id,created_at)
                values ('T','C','1',true,'ACCEPTED',?,?,clock_timestamp())
                """, session, job);
        assertThat(route.eligible(session, job)).isTrue();
        assertThat(route.eligible(session, UUID.randomUUID())).isFalse();
        assertThat(new SlackAttachmentRoute(dataSource, new SlackProperties("", "", "", Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .eligible(session, job)).isFalse();
    }

    @Test
    void waiting_and_clarification_never_release_artifacts_and_later_answer_releases_only_its_own_job() {
        MessageWorkClaim paused = claim("retained-evidence");
        ToolOutput old = save(paused, 1, 0, "Paused draft must remain private");
        append(paused, old);
        conversations.enterWrapUp(paused, Instant.now());
        conversations.append(paused, waitingBatch("Continue or organize?"), Instant.now());
        assertThat(released(paused)).isEmpty();

        conversations.receive(new IncomingMessage("retained-evidence", "alice", "unclear", "yes"), MODEL, model -> Optional.of(MODEL));
        MessageWorkClaim unclear = conversations.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        WaitingChoice original = conversations.loadJobControl(unclear).waiting().orElseThrow();
        conversations.reserveModelCall(unclear, ModelCallPurpose.INTENT, 30, Instant.now());
        conversations.resolveFollowUp(unclear, original, FollowUpIntent.CLARIFY, Instant.now());
        conversations.append(unclear, waitingBatch("Which option?"), Instant.now());
        assertThat(released(paused)).isEmpty();
        assertThat(released(unclear)).isEmpty();

        conversations.receive(new IncomingMessage("retained-evidence", "alice", "deliver", "Organize existing evidence"), MODEL, model -> Optional.of(MODEL));
        MessageWorkClaim deliver = conversations.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        assertThat(conversations.loadJobControl(deliver).waiting()).contains(original);
        conversations.reserveModelCall(deliver, ModelCallPurpose.INTENT, 30, Instant.now());
        conversations.resolveFollowUp(deliver, original, FollowUpIntent.DELIVER, Instant.now());
        ModelCallReservationResult.Reserved call = (ModelCallReservationResult.Reserved) conversations.reserveModelCall(
                deliver, ModelCallPurpose.EXECUTION, 30, Instant.now());
        assertThat(call).isEqualTo(new ModelCallReservationResult.Reserved(2, 1));
        ToolOutput current = save(deliver, call.totalOrdinal(), 0, "Confirmed facts; uncovered areas marked unknown");
        append(deliver, current);
        assertThat(released(deliver)).isEmpty();
        finish(deliver);
        assertThat(released(deliver)).hasSize(1);
        assertThat(released(paused)).isEmpty();
        assertThat(count("select count(*) from generated_artifact where released")).isEqualTo(1);
    }

    @Test
    void cancellation_wins_over_waiting_commit_and_never_releases_the_staged_report() {
        MessageWorkClaim claim = claim("cancel-waiting");
        append(claim, save(claim, 1, 0, "Unreleased report"));
        conversations.enterWrapUp(claim, Instant.now());
        conversations.requestCancellation(claim.messageJobId());
        assertThat(conversations.append(claim, waitingBatch("This question must not be committed"), Instant.now()))
                .isEqualTo(JobStatus.CANCELLED);
        assertThat(released(claim)).isEmpty();
        assertThat(count("select count(*) from session_waiting_choice")).isZero();
        assertThat(conversations.loadHistory(claim.sessionId()).stream().filter(AssistantMessage.class::isInstance)).isEmpty();
    }

    private static ConversationStore.MessageBatch waitingBatch(String text) {
        return new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData(text)), ConversationStore.JobUpdate.COMPLETE,
                Optional.empty(), Optional.empty(), Optional.empty(), ConversationStore.CompletionDisposition.WAITING);
    }

    private MessageWorkClaim claim(String key) {
        conversations.receive(new IncomingMessage(key, "alice", key, "hello"), MODEL, model -> Optional.of(MODEL));
        MessageWorkClaim claim = conversations.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
        conversations.reserveModelCall(claim, ModelCallPurpose.EXECUTION, 30, Instant.now());
        return claim;
    }

    private ToolOutput save(MessageWorkClaim claim, long call, int ordinal, String body) {
        Map<String, Object> arguments = Map.of("title", "Report", "content", body);
        ArtifactEncoder.Encoded encoded = ArtifactEncoder.markdown(arguments);
        ArtifactStore.Saved saved = artifacts.create(TestToolInvocation.of(claim, call, ordinal), encoded.title(), encoded.extension(),
                encoded.mediaType(), encoded.content(), "createMarkdownReport", arguments, encoded.disclosures());
        sourceInputs.put(saved.receipt().artifactId().toString(), arguments);
        return new ToolOutput(false, saved.result(), Optional.of(saved.receipt()));
    }

    private void append(MessageWorkClaim claim, ToolOutput output) {
        ToolCallId call = new ToolCallId("report-call");
        Map<?, ?> result = (Map<?, ?>) output.result();
        String artifactId = output.artifactReceipt().map(receipt -> receipt.artifactId().toString())
                .orElseGet(() -> (String) result.get("artifactId"));
        conversations.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(Optional.of("Saving report"), List.of(
                        new ConversationStore.ToolCallData(call, "createMarkdownReport", sourceInputs.get(artifactId)))),
                new ConversationStore.ToolObservationData(call, "createMarkdownReport", output.asStructuredValue(), output.artifactReceipt())),
                ConversationStore.JobUpdate.KEEP_WORKING, ConversationStore.CompletionDisposition.NONE), Instant.now());
    }

    private void finish(MessageWorkClaim claim) {
        conversations.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE, ConversationStore.CompletionDisposition.ANSWER), Instant.now());
    }

    private List<ArtifactStore.Artifact> released(MessageWorkClaim claim) {
        return artifacts.released(UUID.fromString(claim.sessionId().value()), UUID.fromString(claim.messageJobId().value()));
    }

    private void expire(MessageWorkClaim claim) {
        jdbc.update("update message_job set locked_until=clock_timestamp()-interval '1 millisecond' where message_job_id=?",
                UUID.fromString(claim.messageJobId().value()));
    }

    private int count(String sql) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class));
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
    }
}
