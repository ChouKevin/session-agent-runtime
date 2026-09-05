package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ConversationStoreFailure;
import com.java.system.sessionagent.conversation.port.out.StaleWorkClaimException;
import com.java.system.sessionagent.model.ConversationHistoryProjector;
import com.java.system.sessionagent.model.InvalidConversationHistoryException;
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
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    @Test
    void creates_only_the_final_provider_neutral_conversation_schema() {
        Set<String> tables = Set.copyOf(jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history'
                """, String.class));
        Set<String> observationColumns = Set.copyOf(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'tool_observation'
                """, String.class));
        Set<String> jobColumns = Set.copyOf(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'message_job'
                """, String.class));
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

        assertThat(tables).containsExactlyInAnyOrder("conversation_session", "source_message", "session_message", "user_message",
                "message_job", "assistant_message", "assistant_tool_calls", "model_continuation", "tool_observation", "runtime_message",
                "context_usage_checkpoint");
        assertThat(observationColumns).containsExactlyInAnyOrder("session_id", "sequence", "role", "tool_call_id", "tool_name", "output");
        assertThat(jobColumns).contains("model_route_id").doesNotContain("reply_sequence");
        assertThat(roleChecks).contains("USER", "TOOL", "ASSISTANT", "ASSISTANT_TOOL_CALLS", "RUNTIME").doesNotContain("FEEDBACK");
        assertThat(jobChecks).contains("model_calls >= 0").doesNotContain("between 0 and 12");
    }

    @Test
    void atomically_appends_native_assistant_calls_and_tool_results() {
        ConversationStore store = store();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();

        store.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(java.util.Optional.of("I will inspect both."), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "mcp_lookup", java.util.Map.of("query", Map.of("terms", List.of("fees", "late")))),
                        new ConversationStore.ToolCallData(new ToolCallId("call-2"), "mcp_entries", java.util.Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "mcp_lookup", java.util.Map.of("isError", false, "result", java.util.Map.of("hits", List.of(Map.of("id", 7))))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-2"), "mcp_entries", java.util.Map.of("isError", true, "result", Map.of("failure", Map.of("code", "TOOL_TIMEOUT"))))),
                ConversationStore.JobUpdate.COMPLETE), NOW);

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
    void restores_current_job_continuation_after_runtime_reconstruction_and_removes_it_on_completion() {
        ConversationStore originalStore = store();
        MessageReceipt receipt = originalStore.receive(new IncomingMessage("thread", "alice", "continuation", "hello"));
        MessageWorkClaim claim = originalStore.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        ModelContinuation continuation = new ModelContinuation(new ModelRouteId("gemini-primary"), "opaque-v1", new byte[] {1, 2, 3});
        originalStore.bindModelRoute(claim, continuation.modelRouteId());
        originalStore.append(claim, new ConversationStore.MessageBatch(List.of(
                new ConversationStore.AssistantToolCallsData(java.util.Optional.empty(), List.of(
                        new ConversationStore.ToolCallData(new ToolCallId("call-1"), "mcp_lookup", Map.of()))),
                new ConversationStore.ToolObservationData(new ToolCallId("call-1"), "mcp_lookup", Map.of("isError", false, "result", Map.of()))),
                ConversationStore.JobUpdate.KEEP_WORKING, java.util.Optional.of(continuation)), NOW);

        ConversationStore reconstructedStore = store();
        assertThat(reconstructedStore.loadContinuations(claim)).containsOnlyKeys(new com.java.system.sessionagent.conversation.domain.SessionSequence(2));
        assertThat(reconstructedStore.loadContinuations(claim).get(new com.java.system.sessionagent.conversation.domain.SessionSequence(2)))
                .isEqualTo(continuation);

        reconstructedStore.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("done")),
                ConversationStore.JobUpdate.COMPLETE), NOW.plusSeconds(1));

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
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
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
                            ConversationStore.JobUpdate.KEEP_WORKING), NOW));
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
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
        MessageWorkClaim claim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
        store.append(claim, new ConversationStore.MessageBatch(List.of(new ConversationStore.AssistantData("original")),
                ConversationStore.JobUpdate.COMPLETE), NOW);

        assertThatThrownBy(() -> jdbcTemplate.update("update assistant_message set message = 'changed'"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("delete from assistant_message"))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.loadHistory(receipt.sessionId())).extracting(message -> message.sequence().value()).containsExactly(1L, 2L);
    }

    @Test
    void rolls_back_the_entire_native_batch_when_a_later_tool_result_insert_fails() {
        ConversationStore store = store();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
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
                        new ConversationStore.UsageCheckpointData(descriptor, 1, 50, 20, 70,
                                "a".repeat(64), 0))), NOW)).isInstanceOf(RuntimeException.class);

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
    void rejects_whitespace_only_tool_and_runtime_fields_before_they_can_poison_history() {
        ConversationStore store = store();
        MessageReceipt receipt = store.receive(new IncomingMessage("thread", "alice", "source", "hello"));
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
            MessageReceipt receipt = store().receive(new IncomingMessage("thread-" + index, "alice", "source-" + index, "hello"));
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
        MessageReceipt firstReceipt = store.receive(new IncomingMessage("thread", "alice", "source-1", "first"));
        MessageReceipt secondReceipt = store.receive(new IncomingMessage("thread", "alice", "source-2", "second"));
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

        assertThatThrownBy(() -> new ConversationHistoryProjector(new ObjectMapper()).project(store.loadHistory(firstReceipt.sessionId())))
                .isInstanceOf(InvalidConversationHistoryException.class);
        assertThat(store.readJob(firstClaim.messageJobId())).hasValueSatisfying(job -> assertThat(job.status()).isEqualTo(JobStatus.WORKING));
    }

    private void insertMalformedAssistantCalls(MessageReceipt receipt, MessageWorkClaim claim, String calls) {
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource(), "JDBC data source must not be null");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(claim.messageJobId().value());
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update("insert into session_message(session_id, sequence, message_job_id, role, created_at) values (?, 2, ?, 'ASSISTANT_TOOL_CALLS', ?)",
                    sessionId, jobId, Timestamp.from(NOW));
            jdbcTemplate.update("insert into assistant_tool_calls(session_id, sequence, message, calls) values (?, 2, null, ?::jsonb)",
                    sessionId, calls);
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

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
