package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.ModelCallRecord;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.port.out.ModelCallRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PostgresModelCallRecorder implements ModelCallRecorder {

    private static final String INSERT_MODEL_CALL_RECORD = """
            insert into model_call_record(
                diagnostic_id, session_id, message_job_id, runtime_call_ordinal, provider_attempt,
                phase, outcome, model_name, raw_prompt, raw_completion, raw_tool_calls,
                finish_reason, response_error, provider_error,
                prompt_tokens, completion_tokens, total_tokens, started_at, completed_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresModelCallRecorder(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(Objects.requireNonNull(dataSource, "Data source must not be null"));
    }

    @Override
    public void record(ModelCallRecord record) {
        ModelCallRecord requiredRecord = Objects.requireNonNull(record, "Model call record must not be null");
        jdbcTemplate.execute(INSERT_MODEL_CALL_RECORD, (PreparedStatementCallback<Void>) statement -> {
            statement.setObject(1, requiredRecord.id());
            statement.setObject(2, id(requiredRecord.sessionId().value()));
            statement.setObject(3, id(requiredRecord.messageJobId().value()));
            statement.setInt(4, requiredRecord.runtimeCallOrdinal());
            statement.setInt(5, requiredRecord.providerAttempt());
            statement.setString(6, requiredRecord.phase().name());
            statement.setString(7, requiredRecord.outcome().name());
            statement.setString(8, requiredRecord.modelName());
            statement.setString(9, requiredRecord.rawPrompt());
            setOptionalString(statement, 10, requiredRecord.rawCompletion());
            setOptionalString(statement, 11, requiredRecord.rawToolCalls());
            setOptionalString(statement, 12, requiredRecord.finishReason());
            setOptionalString(statement, 13, requiredRecord.responseError());
            setOptionalString(statement, 14, requiredRecord.providerError());
            setUsage(statement, requiredRecord.usage());
            statement.setObject(18, timestamp(requiredRecord.startedAt()));
            statement.setObject(19, timestamp(requiredRecord.completedAt()));
            statement.executeUpdate();
            return null;
        });
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }

    private static void setOptionalString(
            PreparedStatement statement,
            int index,
            Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
            return;
        }
        statement.setNull(index, Types.VARCHAR);
    }

    private static void setUsage(PreparedStatement statement, ModelUsage usage) throws SQLException {
        if (usage.available()) {
            statement.setLong(15, usage.promptTokens());
            statement.setLong(16, usage.completionTokens());
            statement.setLong(17, usage.totalTokens());
            return;
        }
        statement.setNull(15, Types.BIGINT);
        statement.setNull(16, Types.BIGINT);
        statement.setNull(17, Types.BIGINT);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
