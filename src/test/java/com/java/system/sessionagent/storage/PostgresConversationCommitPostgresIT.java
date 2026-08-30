package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.FeedbackMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.tool.application.ToolResultEnvelopeFactory;
import com.java.system.sessionagent.tool.domain.ToolExecution;
import com.java.system.sessionagent.tool.domain.ToolKind;
import com.java.system.sessionagent.tool.domain.ToolName;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresConversationCommitPostgresIT {
    private static final String MODEL_CONTEXT = "dGVzdA==";

    private static final Instant NOW = Instant.parse("2026-08-16T03:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        POSTGRES.start();
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void clearSchema() {
        flyway().clean();
    }

    @Test
    void atomically_commits_a_tool_then_an_opaque_terminal_assistant_reply() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        appendSource(store, claim);
        store.appendAssistant(claim, "done", NOW);

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tool_message", Integer.class)).isEqualTo(1);
        Integer citationTables = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'assistant_citation'
                """, Integer.class);
        Integer citeableColumns = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'tool_message' and column_name = 'citeable'
                """, Integer.class);
        assertThat(citationTables).isZero();
        assertThat(citeableColumns).isZero();
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class, id(receipt)))
                .isEqualTo("DONE");
        assertThat(store.readJob(receipt.messageJobId())).hasValueSatisfying(job -> assertThat(job.replySequence()).isPresent());
        java.util.List<SessionMessage> history = store.loadHistory(receipt.sessionId());
        assertThat(history).extracting(message -> message.sequence().value()).containsExactly(1L, 2L, 3L);
        ToolMessage tool = (ToolMessage) history.get(1);
        AssistantMessage assistant = (AssistantMessage) history.get(2);
        assertThat(tool.resultJson()).isEqualTo("{}");
        assertThat(assistant.message()).isEqualTo("done");
    }

    @Test
    void hides_later_fifo_input_from_a_claimed_job_history() {
        ConversationStore store = newStore();
        MessageReceipt first = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "first"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        store.receive(new IncomingMessage("thread-1", "bob", "source-2", "later"));

        java.util.List<SessionMessage> history = store.loadHistory(claim.sessionId(), first.messageJobId());

        assertThat(history).hasSize(1);
        assertThat(((com.java.system.sessionagent.conversation.domain.UserMessage) history.getFirst()).message()).isEqualTo("first");
    }

    @Test
    void loads_completed_prior_job_history_and_excludes_later_input_for_the_current_job() {
        ConversationStore store = newStore();
        MessageReceipt first = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "first"));
        MessageWorkClaim firstClaim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        store.appendFeedback(firstClaim, "COMPLETE", "safe", true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW);
        MessageReceipt second = store.receive(new IncomingMessage("thread-1", "alice", "source-2", "second"));
        MessageWorkClaim secondClaim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        store.receive(new IncomingMessage("thread-1", "alice", "source-3", "later"));

        java.util.List<SessionMessage> history = store.loadHistory(secondClaim.sessionId(), second.messageJobId());

        assertThat(history.stream().filter(com.java.system.sessionagent.conversation.domain.UserMessage.class::isInstance)
                .map(com.java.system.sessionagent.conversation.domain.UserMessage.class::cast)
                .map(com.java.system.sessionagent.conversation.domain.UserMessage::message))
                .containsExactly("first", "second");
        assertThat(history).extracting(com.java.system.sessionagent.conversation.domain.SessionMessage::sequence)
                .extracting(sequence -> sequence.value()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void discards_an_append_after_the_claim_fence_is_replaced_without_allocating_a_sequence() {
        ConversationStore store = newStore();
        store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim original = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?", UUID.fromString(original.messageJobId().value()));
        store.claimNext("worker-2", Duration.ofSeconds(30)).orElseThrow();

        assertThatThrownBy(() -> store.appendFeedback(original, "MODEL_OUTPUT_INVALID", "safe", false,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW))
                .isExactlyInstanceOf(StaleWorkClaimException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
    }

    @Test
    void treats_a_non_uuid_result_id_as_an_absent_result() {
        ConversationStore store = newStore();

        assertThat(store.readResult(new ResultId("hallucinated-result-id"))).isEmpty();
    }

    @Test
    void persists_an_arbitrary_opaque_reply() {
        ConversationStore store = newStore();
        store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        store.appendAssistant(claim, "{\"opaque\":true}", NOW);

        assertThat(store.readJob(claim.messageJobId())).hasValueSatisfying(job -> assertThat(job.status()).isEqualTo(JobStatus.DONE));
        assertThat(jdbcTemplate.queryForObject("select count(*) from assistant_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select message from assistant_message", String.class)).isEqualTo("{\"opaque\":true}");
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class, UUID.fromString(claim.messageJobId().value()))).isEqualTo("DONE");
    }

    @Test
    void keeps_nonterminal_feedback_working_and_completes_terminal_feedback_atomically() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        store.appendFeedback(claim, "MODEL_OUTPUT_INVALID", "safe", false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW);
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class, id(receipt))).isEqualTo("WORKING");
        store.appendFeedback(claim, "CALL_LIMIT_REACHED", "safe", true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW);

        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class, id(receipt))).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("select count(*) from feedback_message", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select reply_sequence from message_job where message_job_id = ?", Long.class, id(receipt))).isEqualTo(3L);
    }

    @Test
    void preserves_canonical_tool_payload_text_through_append_history_and_result_reads() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        ResultId resultId = new ResultId(UUID.randomUUID().toString());
        String arguments = "{\"limit\":2,\"repositoryId\":\"payment\"}";
        ToolExecution execution = new ToolExecution(new ToolName("source"), "v1", ToolKind.SOURCE, arguments, Optional.of("payment"),
                Optional.of("rev-1"), "{\"nested\":{\"zeta\":2,\"alpha\":1}}");
        ToolResultEnvelopeFactory envelopeFactory = new ToolResultEnvelopeFactory();
        String result = envelopeFactory.envelope(resultId.value(), envelopeFactory.validate(execution));

        ToolMessage appended = store.appendTool(claim, resultId, "source-call", MODEL_CONTEXT, new ConversationStore.ToolData(
                "source", "v1", ToolKind.SOURCE, arguments, Optional.of("payment"), Optional.of("rev-1"), result), NOW);
        String rejectedArguments = "{not-json}";
        store.appendFeedback(claim, "INVALID_TOOL_INPUT", "safe", false, Optional.of("rejected-call"),
                Optional.of("source"), Optional.of(rejectedArguments), Optional.of(MODEL_CONTEXT), NOW);
        java.util.List<SessionMessage> history = store.loadHistory(receipt.sessionId());
        ToolMessage reloaded = (ToolMessage) history.get(1);
        FeedbackMessage feedback = (FeedbackMessage) history.get(2);

        assertThat(jdbcTemplate.queryForObject("select data_type from information_schema.columns where table_name = 'tool_message' and column_name = 'arguments_json'", String.class)).isEqualTo("text");
        assertThat(jdbcTemplate.queryForObject("select data_type from information_schema.columns where table_name = 'tool_message' and column_name = 'result_json'", String.class)).isEqualTo("text");
        assertThat(jdbcTemplate.queryForObject("select tool_kind from tool_message where result_id = ?", String.class, UUID.fromString(resultId.value())))
                .isEqualTo("SOURCE");
        assertThat(jdbcTemplate.queryForObject("select data_type from information_schema.columns where table_name = 'feedback_message' and column_name = 'rejected_arguments_json'", String.class)).isEqualTo("text");
        assertThat(appended.arguments()).isEqualTo(arguments);
        assertThat(appended.modelContext()).isEqualTo(MODEL_CONTEXT);
        assertThat(appended.resultJson()).isEqualTo(result);
        assertThat(reloaded.arguments()).isEqualTo(arguments);
        assertThat(reloaded.modelContext()).isEqualTo(MODEL_CONTEXT);
        assertThat(reloaded.resultJson()).isEqualTo(result);
        assertThat(feedback.rejectedArguments()).contains(rejectedArguments);
        assertThat(feedback.modelContext()).contains(MODEL_CONTEXT);
        assertThat(store.readResult(resultId)).hasValueSatisfying(projection -> {
            assertThat(projection.canonicalArguments()).isEqualTo(arguments);
            assertThat(projection.resultJson()).isEqualTo(result);
        });
    }

    @Test
    void rejects_invalid_successful_tool_json_and_rolls_back_the_append() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();

        assertThatThrownBy(() -> store.appendTool(claim, new ResultId(UUID.randomUUID().toString()), "source-call", MODEL_CONTEXT,
                new ConversationStore.ToolData("source", "v1", ToolKind.SOURCE, "{not-json}", Optional.of("payment"), Optional.of("rev-1"), "{\"data\":{}}"), NOW))
                .isInstanceOf(ConversationStoreFailure.class)
                .extracting(exception -> ((ConversationStoreFailure) exception).kind())
                .isEqualTo(ConversationStoreFailure.Kind.CONTRACT);

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from tool_message", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class, id(receipt))).isEqualTo("WORKING");
    }

    @Test
    void discards_an_append_after_the_current_claim_expires_without_replacement() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofSeconds(30)).orElseThrow();
        jdbcTemplate.update("update message_job set locked_until = clock_timestamp() - interval '1 millisecond' where message_job_id = ?",
                id(receipt));

        assertThatThrownBy(() -> store.appendFeedback(claim, "MODEL_OUTPUT_INVALID", "safe", false,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW.plusSeconds(31)))
                .isExactlyInstanceOf(StaleWorkClaimException.class);

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
    }

    @Test
    void rejects_an_append_that_expires_while_waiting_for_the_session_lock() throws Exception {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread-1", "alice", "source-1", "hello"));
        MessageWorkClaim claim = store.claimNext("worker-1", Duration.ofMillis(10)).orElseThrow();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (java.sql.Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "select session_id from conversation_session where session_id = ? for update")) {
                statement.setObject(1, UUID.fromString(claim.sessionId().value()));
                statement.executeQuery();
            }
            Future<?> append = executor.submit(() -> store.appendFeedback(claim, "MODEL_OUTPUT_INVALID", "safe", false,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW));
            awaitSessionLockWait();
            try (java.sql.PreparedStatement statement = connection.prepareStatement("select pg_sleep(0.05)")) {
                statement.executeQuery();
            }
            connection.commit();

            assertThatThrownBy(append::get).hasCauseInstanceOf(StaleWorkClaimException.class);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select next_sequence from conversation_session", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("select status from message_job where message_job_id = ?", String.class, id(receipt)))
                .isEqualTo("WORKING");
        assertThat(jdbcTemplate.queryForObject("select claim_number from message_job where message_job_id = ?", Long.class, id(receipt)))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("select model_calls + retry_count from message_job where message_job_id = ?", Integer.class,
                id(receipt))).isZero();
    }

    private ConversationStore newStore() {
        return newStoreAt(NOW);
    }

    private ConversationStore newStoreAt(Instant now) {
        return new PostgresConversationStore(dataSource(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private ResultId appendSource(ConversationStore store, MessageWorkClaim claim) {
        ResultId resultId = new ResultId(UUID.randomUUID().toString());
        store.appendTool(claim, resultId, "source-call", MODEL_CONTEXT, new ConversationStore.ToolData(
                "source", "v1", ToolKind.SOURCE, "{\"repositoryId\":\"payment\"}", Optional.of("payment"), Optional.of("rev-1"), "{}"), NOW);
        return resultId;
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load();
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private UUID id(MessageReceipt receipt) {
        return UUID.fromString(receipt.messageJobId().value());
    }

    private void awaitSessionLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waitingConnections = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'",
                    Integer.class);
            if (Objects.requireNonNull(waitingConnections, "waiting connection count is required") > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("append did not wait for the session lock");
    }
}
