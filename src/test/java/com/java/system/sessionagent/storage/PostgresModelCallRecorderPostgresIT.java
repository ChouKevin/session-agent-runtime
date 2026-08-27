package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.ModelCallOutcome;
import com.java.system.sessionagent.conversation.domain.ModelCallPhase;
import com.java.system.sessionagent.conversation.domain.ModelCallRecord;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.ModelCallRecorder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresModelCallRecorderPostgresIT {

    private static final Instant NOW = Instant.parse("2026-08-26T02:00:00Z");

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
    void storesModelDiagnosticsWithoutCreatingASessionMessage() {
        ConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.systemUTC());
        MessageReceipt receipt = conversationStore.receive(new IncomingMessage(
                "thread-1", "Alice", "source-1", "Question"));
        ModelCallRecorder recorder = new PostgresModelCallRecorder(dataSource());
        ModelCallRecord record = completeRecord(receipt);

        recorder.record(record);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from model_call_record where diagnostic_id = ?", record.id());
        assertThat(row.get("diagnostic_id")).isEqualTo(record.id());
        assertThat(row.get("session_id")).isEqualTo(UUID.fromString(receipt.sessionId().value()));
        assertThat(row.get("message_job_id")).isEqualTo(UUID.fromString(receipt.messageJobId().value()));
        assertThat(row.get("runtime_call_ordinal")).isEqualTo(1);
        assertThat(row.get("provider_attempt")).isEqualTo(1);
        assertThat(row.get("phase")).isEqualTo("PLAN");
        assertThat(row.get("outcome")).isEqualTo("ANSWER_READY");
        assertThat(row.get("model_name")).isEqualTo("gemini-3.1-flash-lite");
        assertThat(row.get("raw_prompt")).isEqualTo("Prompt snapshot");
        assertThat(row.get("raw_completion")).isEqualTo("Ready to answer.");
        assertThat(row).containsEntry("raw_tool_calls", null);
        assertThat(row.get("finish_reason")).isEqualTo("STOP");
        assertThat(row).containsEntry("decode_error", null);
        assertThat(row).containsEntry("provider_error", null);
        assertThat(row.get("prompt_tokens")).isEqualTo(7L);
        assertThat(row.get("completion_tokens")).isEqualTo(3L);
        assertThat(row.get("total_tokens")).isEqualTo(10L);
        OffsetDateTime storedStartedAt = jdbcTemplate.queryForObject(
                "select started_at from model_call_record where diagnostic_id = ?",
                (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class), record.id());
        OffsetDateTime storedCompletedAt = jdbcTemplate.queryForObject(
                "select completed_at from model_call_record where diagnostic_id = ?",
                (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class), record.id());
        assertThat(storedStartedAt).isEqualTo(timestamp(NOW));
        assertThat(storedCompletedAt).isEqualTo(timestamp(NOW.plusMillis(50)));
        assertThat(conversationStore.loadHistory(receipt.sessionId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from session_message", Integer.class)).isEqualTo(1);
    }

    @Test
    void storesProviderFailureWithUnavailableUsageAndNullCompletion() {
        ConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.systemUTC());
        MessageReceipt receipt = conversationStore.receive(new IncomingMessage(
                "thread-1", "Alice", "source-1", "Question"));
        ModelCallRecorder recorder = new PostgresModelCallRecorder(dataSource());
        ModelCallRecord record = new ModelCallRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000112"),
                receipt.sessionId(), receipt.messageJobId(), 1, 1,
                ModelCallPhase.PLAN, ModelCallOutcome.PROVIDER_FAILURE,
                "gemini-3.1-flash-lite", "Prompt snapshot",
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("upstream timeout"), new ModelUsage(0, 0, 0, false),
                NOW, NOW.plusMillis(50));

        recorder.record(record);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from model_call_record where diagnostic_id = ?", record.id());
        assertThat(row).containsEntry("raw_completion", null);
        assertThat(row).containsEntry("provider_error", "upstream timeout");
        assertThat(row).containsEntry("prompt_tokens", null);
        assertThat(row).containsEntry("completion_tokens", null);
        assertThat(row).containsEntry("total_tokens", null);
    }

    @ParameterizedTest
    @MethodSource("appendOnlyStatements")
    void rejectsUpdatesAndDeletesWhilePreservingTheRecordedDiagnostic(String sql) {
        MessageReceipt receipt = receive();
        ModelCallRecord record = completeRecord(receipt);
        new PostgresModelCallRecorder(dataSource()).record(record);

        assertThatThrownBy(() -> jdbcTemplate.update(sql, record.id()))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from model_call_record where diagnostic_id = ?", Integer.class, record.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select model_name from model_call_record where diagnostic_id = ?", String.class, record.id()))
                .isEqualTo(record.modelName());
    }

    @ParameterizedTest
    @MethodSource("invalidRecordValues")
    void rejectsRecordsThatViolateTheDomainDiagnosticInvariant(
            ModelCallPhase phase,
            ModelCallOutcome outcome,
            Optional<String> rawCompletion,
            Optional<String> rawToolCalls,
            Optional<String> decodeError) {
        MessageReceipt receipt = receive();
        ModelCallRecord validRecord = completeRecord(receipt);

        assertThatThrownBy(() -> insertRaw(
                validRecord, phase, outcome, rawCompletion, rawToolCalls, decodeError, Optional.empty()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static Stream<Arguments> appendOnlyStatements() {
        return Stream.of(
                Arguments.of("update model_call_record set model_name = 'different' where diagnostic_id = ?"),
                Arguments.of("delete from model_call_record where diagnostic_id = ?"));
    }

    private static Stream<Arguments> invalidRecordValues() {
        return Stream.of(
                Arguments.of(ModelCallPhase.FINAL_REPLY, ModelCallOutcome.ANSWER_READY,
                        Optional.of("Ready to answer."), Optional.empty(), Optional.empty()),
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.INVALID_RESPONSE,
                        Optional.of("Malformed"), Optional.empty(), Optional.empty()),
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.TOOL_CALL,
                        Optional.of("Answer text"), Optional.of("{\"calls\":[]}"), Optional.empty()),
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.ANSWER_READY,
                        Optional.empty(), Optional.empty(), Optional.empty()),
                Arguments.of(ModelCallPhase.PLAN, ModelCallOutcome.ANSWER_READY,
                        Optional.of("   "), Optional.empty(), Optional.empty()));
    }

    private MessageReceipt receive() {
        ConversationStore conversationStore = new PostgresConversationStore(dataSource(), Clock.systemUTC());
        return conversationStore.receive(new IncomingMessage("thread-1", "Alice", "source-1", "Question"));
    }

    private ModelCallRecord completeRecord(MessageReceipt receipt) {
        return new ModelCallRecord(
                UUID.fromString("00000000-0000-0000-0000-000000000111"),
                receipt.sessionId(), receipt.messageJobId(), 1, 1,
                ModelCallPhase.PLAN, ModelCallOutcome.ANSWER_READY,
                "gemini-3.1-flash-lite", "Prompt snapshot",
                Optional.of("Ready to answer."), Optional.empty(), Optional.of("STOP"),
                Optional.empty(), Optional.empty(), new ModelUsage(7, 3, 10, true),
                NOW, NOW.plusMillis(50));
    }

    private void insertRaw(
            ModelCallRecord record,
            ModelCallPhase phase,
            ModelCallOutcome outcome,
            Optional<String> rawCompletion,
            Optional<String> rawToolCalls,
            Optional<String> decodeError,
            Optional<String> providerError) {
        jdbcTemplate.execute("""
                insert into model_call_record(
                    diagnostic_id, session_id, message_job_id, runtime_call_ordinal, provider_attempt,
                    phase, outcome, model_name, raw_prompt, raw_completion, raw_tool_calls,
                    finish_reason, decode_error, provider_error,
                    prompt_tokens, completion_tokens, total_tokens, started_at, completed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (PreparedStatementCallback<Void>) statement -> {
            statement.setObject(1, record.id());
            statement.setObject(2, UUID.fromString(record.sessionId().value()));
            statement.setObject(3, UUID.fromString(record.messageJobId().value()));
            statement.setInt(4, record.runtimeCallOrdinal());
            statement.setInt(5, record.providerAttempt());
            statement.setString(6, phase.name());
            statement.setString(7, outcome.name());
            statement.setString(8, record.modelName());
            statement.setString(9, record.rawPrompt());
            setOptionalString(statement, 10, rawCompletion);
            setOptionalString(statement, 11, rawToolCalls);
            setOptionalString(statement, 12, record.finishReason());
            setOptionalString(statement, 13, decodeError);
            setOptionalString(statement, 14, providerError);
            statement.setLong(15, record.usage().promptTokens());
            statement.setLong(16, record.usage().completionTokens());
            statement.setLong(17, record.usage().totalTokens());
            statement.setObject(18, timestamp(record.startedAt()));
            statement.setObject(19, timestamp(record.completedAt()));
            statement.executeUpdate();
            return null;
        });
    }

    private void setOptionalString(PreparedStatement statement, int index, Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
            return;
        }
        statement.setNull(index, Types.VARCHAR);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
