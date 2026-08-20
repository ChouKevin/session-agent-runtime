package com.java.system.sessionagent.storage;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMessageIntakePostgresIT {

    private static final String SOURCE_TYPE = "http";
    private static final Instant FIXED_NOW = Instant.parse("2026-08-16T02:00:00Z");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        POSTGRES.start();
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbcTemplate = new JdbcTemplate(dataSource());
    }

    @AfterEach
    void clearSchema() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
    }

    @Test
    void persistsTheFirstIntakeAsOneSessionOneUserMessageAndOnePendingJob() {
        ConversationStore store = newStore();

        MessageReceipt receipt = store.receive(message("thread-1", "source-1", "alice", "hello"));

        assertThat(count("conversation_session")).isEqualTo(1);
        assertThat(count("source_message")).isEqualTo(1);
        assertThat(count("session_message")).isEqualTo(1);
        assertThat(count("user_message")).isEqualTo(1);
        assertThat(count("message_job")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select sequence from session_message where session_id = ?", Long.class, uuid(receipt.sessionId().value())))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select status from message_job where message_job_id = ?", String.class, uuid(receipt.messageJobId().value())))
                .isEqualTo("PENDING");
    }

    @Test
    void returnsTheOriginalReceiptAndCreatesNoRowsForAnExactDuplicate() {
        ConversationStore store = newStore();
        IncomingMessage incomingMessage = message("thread-1", "source-1", "alice", "hello");

        MessageReceipt firstReceipt = store.receive(incomingMessage);
        MessageReceipt duplicateReceipt = store.receive(incomingMessage);

        assertThat(duplicateReceipt).isEqualTo(firstReceipt);
        assertThat(count("conversation_session")).isEqualTo(1);
        assertThat(count("source_message")).isEqualTo(1);
        assertThat(count("session_message")).isEqualTo(1);
        assertThat(count("user_message")).isEqualTo(1);
        assertThat(count("message_job")).isEqualTo(1);
    }

    @Test
    void doesNotCreateAnUnusedSessionForAnExactDuplicateWithAnotherSessionKey() {
        ConversationStore store = newStore();
        MessageReceipt originalReceipt = store.receive(message("thread-a", "source-1", "alice", "hello"));

        MessageReceipt duplicateReceipt = store.receive(message("thread-b", "source-1", "alice", "hello"));

        assertThat(duplicateReceipt).isEqualTo(originalReceipt);
        assertThat(count("conversation_session")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversation_session where source_type = ? and session_key = ?",
                Integer.class, SOURCE_TYPE, "thread-b")).isZero();
    }

    @Test
    void rejectsAConflictingDuplicateWithoutAppendingRows() {
        ConversationStore store = newStore();
        store.receive(message("thread-1", "source-1", "alice", "hello"));

        assertThatThrownBy(() -> store.receive(message("thread-2", "source-1", "alice", "different")))
                .isExactlyInstanceOf(MessageConflictException.class)
                .hasMessage("Incoming message conflicts with an existing source message");

        assertThat(count("conversation_session")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversation_session where source_type = ? and session_key = ?",
                Integer.class, SOURCE_TYPE, "thread-2")).isZero();
        assertThat(count("source_message")).isEqualTo(1);
        assertThat(count("session_message")).isEqualTo(1);
        assertThat(count("user_message")).isEqualTo(1);
        assertThat(count("message_job")).isEqualTo(1);
    }

    @Test
    void assignsUniqueConsecutiveSequencesToConcurrentDistinctMessagesInOneSession() throws Exception {
        ConversationStore store = newStore();
        int messageCount = 8;
        CountDownLatch ready = new CountDownLatch(messageCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(messageCount);
        List<Future<MessageReceipt>> receipts = new ArrayList<>();
        try {
            for (int index = 0; index < messageCount; index++) {
                int sourceIndex = index;
                Callable<MessageReceipt> task = () -> {
                    ready.countDown();
                    start.await();
                    return store.receive(message("thread-1", "source-" + sourceIndex, "alice", "message-" + sourceIndex));
                };
                receipts.add(executorService.submit(task));
            }
            ready.await();
            start.countDown();
            for (Future<MessageReceipt> receipt : receipts) {
                receipt.get();
            }
        } finally {
            executorService.shutdownNow();
        }

        List<Long> sequences = jdbcTemplate.queryForList(
                "select sequence from session_message order by sequence", Long.class);
        assertThat(sequences).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void preservesParticipantIdentityAndMessageTextExactly() {
        ConversationStore store = newStore();

        MessageReceipt receipt = store.receive(message(
                "thread-1", "source-1", " participant with spaces ", "  preserve\\nthis\\ttext exactly  "));

        List<String> values = jdbcTemplate.query(
                "select participant_id, message from user_message where session_id = ?",
                (resultSet, rowNumber) -> List.of(resultSet.getString(1), resultSet.getString(2)),
                uuid(receipt.sessionId().value())).get(0);
        assertThat(values).containsExactly(" participant with spaces ", "  preserve\\nthis\\ttext exactly  ");
    }

    @Test
    void makes_session_identity_and_timestamps_immutable_while_allowing_sequence_allocation() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(message("thread-1", "source-1", "alice", "hello"));
        UUID sessionId = uuid(receipt.sessionId().value());

        assertThatThrownBy(() -> jdbcTemplate.update("update conversation_session set session_key = 'other' where session_id = ?", sessionId))
                .hasMessageContaining("conversation session identity is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("update conversation_session set created_at = clock_timestamp() where session_id = ?", sessionId))
                .hasMessageContaining("conversation session identity is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update("delete from conversation_session where session_id = ?", sessionId))
                .hasMessageContaining("conversation sessions must not be deleted");
        store.receive(message("thread-1", "source-2", "alice", "again"));

        assertThat(jdbcTemplate.queryForList("select sequence from session_message where session_id = ? order by sequence", Long.class, sessionId))
                .containsExactly(1L, 2L);
    }

    @Test
    void rejectsOrphanSourceAndBaseRowsAndDoneJobsWhoseReplyBelongsToAnotherJobAtCommit() {
        assertThatThrownBy(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            insertSession(connection, sessionId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into source_message(source_type, source_message_id, session_id, user_message_sequence, content_hash, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, SOURCE_TYPE);
                statement.setString(2, "orphan-source");
                statement.setObject(3, sessionId);
                statement.setLong(4, 1L);
                statement.setString(5, "0".repeat(64));
                statement.setObject(6, timestamp(FIXED_NOW));
                statement.executeUpdate();
            }
        })).isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            insertSession(connection, sessionId);
            insertBaseMessage(connection, sessionId, 1L, null, "USER");
        })).isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            UUID firstJobId = UUID.randomUUID();
            UUID secondJobId = UUID.randomUUID();
            insertSession(connection, sessionId);
            insertUserJob(connection, sessionId, firstJobId, 1L, "source-1");
            insertUserJob(connection, sessionId, secondJobId, 2L, "source-2");
            insertBaseMessage(connection, sessionId, 3L, secondJobId, "ASSISTANT");
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into assistant_message(session_id, sequence, role, message)
                    values (?, ?, 'ASSISTANT', ?)
                    """)) {
                statement.setObject(1, sessionId);
                statement.setLong(2, 3L);
                statement.setString(3, "answer");
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    update message_job
                    set status = 'DONE', completed_at = ?, reply_sequence = ?
                    where message_job_id = ?
                    """)) {
                statement.setObject(1, timestamp(FIXED_NOW));
                statement.setLong(2, 3L);
                statement.setObject(3, firstJobId);
                statement.executeUpdate();
            }
        })).isInstanceOf(SQLException.class);
    }

    @Test
    void rejectsDuplicateToolModelCallIdsWithoutCommittingPartialRows() {
        UUID sessionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(connection -> {
            insertSession(connection, sessionId);
            insertUserJob(connection, sessionId, jobId, 1L, "source-1");
            insertToolMessage(connection, sessionId, jobId, 2L, "model-call-1");
            insertToolMessage(connection, sessionId, jobId, 3L, "model-call-1");
        })).isInstanceOf(SQLException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversation_session where session_id = ?", Integer.class, sessionId)).isZero();
    }

    @Test
    void rejectsCrossSessionMessageJobBindingsAndDuplicateJobsForOneUserMessage() {
        UUID firstSessionId = UUID.randomUUID();
        UUID secondSessionId = UUID.randomUUID();
        UUID firstJobId = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(connection -> {
            insertSession(connection, firstSessionId, "thread-a");
            insertSession(connection, secondSessionId, "thread-b");
            insertUserJob(connection, firstSessionId, firstJobId, 1L, "source-a");
            insertToolMessage(connection, secondSessionId, firstJobId, 1L, "model-call-1");
        })).isInstanceOf(SQLException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversation_session where session_id in (?, ?)",
                Integer.class, firstSessionId, secondSessionId)).isZero();

        UUID sessionId = UUID.randomUUID();
        UUID firstUserJobId = UUID.randomUUID();
        UUID duplicateUserJobId = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(connection -> {
            insertSession(connection, sessionId);
            insertUserJob(connection, sessionId, firstUserJobId, 1L, "source-1");
            insertMessageJob(connection, sessionId, duplicateUserJobId, 1L);
        })).isInstanceOf(SQLException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversation_session where session_id = ?", Integer.class, sessionId)).isZero();
    }

    @Test
    void rejectsDeletingOrReassigningACommittedMessageJob() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(message("thread-1", "source-1", "alice", "hello"));
        UUID messageJobId = uuid(receipt.messageJobId().value());
        UUID reassignedJobId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from message_job where message_job_id = ?", messageJobId))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from message_job where message_job_id = ?", Integer.class, messageJobId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update message_job set message_job_id = ? where message_job_id = ?", reassignedJobId, messageJobId))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from message_job where message_job_id = ?", Integer.class, messageJobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from message_job where message_job_id = ?", Integer.class, reassignedJobId)).isZero();

        assertThat(jdbcTemplate.update(
                "update message_job set status = 'RETRY', available_at = ? where message_job_id = ?",
                timestamp(FIXED_NOW.plusSeconds(30)), messageJobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from message_job where message_job_id = ?", String.class, messageJobId)).isEqualTo("RETRY");
    }

    @Test
    void requiresFeedbackToolMetadataToBeAllPresentOrAllAbsent() {
        assertThatThrownBy(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            insertSession(connection, sessionId, "thread-model-only");
            insertUserJob(connection, sessionId, jobId, 1L, "source-model-only");
            insertFeedbackMessage(connection, sessionId, jobId, 2L, "model-call-1", "tool", null, "AQIDBA==");
        })).isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            insertSession(connection, sessionId, "thread-arguments-only");
            insertUserJob(connection, sessionId, jobId, 1L, "source-arguments-only");
            insertFeedbackMessage(connection, sessionId, jobId, 2L, null, null, "{}", null);
        })).isInstanceOf(SQLException.class);

        assertThatCode(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            insertSession(connection, sessionId, "thread-general-feedback");
            insertUserJob(connection, sessionId, jobId, 1L, "source-general-feedback");
            insertFeedbackMessage(connection, sessionId, jobId, 2L, null, null, null, null);
        })).doesNotThrowAnyException();

        assertThatThrownBy(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            insertSession(connection, sessionId, "thread-tool-feedback-without-context");
            insertUserJob(connection, sessionId, jobId, 1L, "source-tool-feedback-without-context");
            insertFeedbackMessage(connection, sessionId, jobId, 2L, "model-call-2", "tool", "{}", null);
        })).isInstanceOf(SQLException.class);

        assertThatCode(() -> inTransaction(connection -> {
            UUID sessionId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            insertSession(connection, sessionId, "thread-tool-feedback");
            insertUserJob(connection, sessionId, jobId, 1L, "source-tool-feedback");
            insertFeedbackMessage(connection, sessionId, jobId, 2L, "model-call-3", "tool", "{}", "AQIDBA==");
        })).doesNotThrowAnyException();
    }

    @Test
    void rejectsUpdatesAndDeletesOfCommittedSourceBaseAndDetailRows() {
        ConversationStore store = newStore();
        MessageReceipt receipt = store.receive(message("thread-1", "source-1", "alice", "hello"));
        UUID sessionId = uuid(receipt.sessionId().value());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update source_message set content_hash = ? where session_id = ?", "1".repeat(64), sessionId))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from source_message where session_id = ?", sessionId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update session_message set created_at = ? where session_id = ?", timestamp(FIXED_NOW.plusSeconds(1)), sessionId))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from user_message where session_id = ?", sessionId)).isInstanceOf(RuntimeException.class);
    }

    private ConversationStore newStore() {
        return new PostgresConversationStore(dataSource(), Clock.fixed(FIXED_NOW, java.time.ZoneOffset.UTC));
    }

    private IncomingMessage message(String sessionKey, String sourceMessageId, String participantId, String message) {
        return new IncomingMessage(sessionKey, participantId, sourceMessageId, message);
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private int count(String tableName) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class));
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void inTransaction(SqlWork work) throws Exception {
        try (Connection connection = dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.execute(connection);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void insertSession(Connection connection, UUID sessionId) throws SQLException {
        insertSession(connection, sessionId, "thread-1");
    }

    private void insertSession(Connection connection, UUID sessionId, String sessionKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into conversation_session(session_id, source_type, session_key, created_at)
                values (?, ?, ?, ?)
                """)) {
            statement.setObject(1, sessionId);
            statement.setString(2, SOURCE_TYPE);
            statement.setString(3, sessionKey);
            statement.setObject(4, timestamp(FIXED_NOW));
            statement.executeUpdate();
        }
    }

    private void insertUserJob(Connection connection, UUID sessionId, UUID jobId, long sequence, String sourceMessageId)
            throws SQLException {
        insertBaseMessage(connection, sessionId, sequence, null, "USER");
        try (PreparedStatement source = connection.prepareStatement("""
                insert into source_message(source_type, source_message_id, session_id, user_message_sequence, content_hash, created_at)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            source.setString(1, SOURCE_TYPE);
            source.setString(2, sourceMessageId);
            source.setObject(3, sessionId);
            source.setLong(4, sequence);
            source.setString(5, "0".repeat(64));
            source.setObject(6, timestamp(FIXED_NOW));
            source.executeUpdate();
        }
        try (PreparedStatement user = connection.prepareStatement("""
                insert into user_message(session_id, sequence, participant_id, source_type, source_message_id, message)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            user.setObject(1, sessionId);
            user.setLong(2, sequence);
            user.setString(3, "alice");
            user.setString(4, SOURCE_TYPE);
            user.setString(5, sourceMessageId);
            user.setString(6, "hello");
            user.executeUpdate();
        }
        insertMessageJob(connection, sessionId, jobId, sequence);
    }

    private void insertMessageJob(Connection connection, UUID sessionId, UUID jobId, long sequence) throws SQLException {
        try (PreparedStatement job = connection.prepareStatement("""
                insert into message_job(message_job_id, session_id, user_message_sequence, status, available_at, created_at)
                values (?, ?, ?, 'PENDING', ?, ?)
                """)) {
            job.setObject(1, jobId);
            job.setObject(2, sessionId);
            job.setLong(3, sequence);
            job.setObject(4, timestamp(FIXED_NOW));
            job.setObject(5, timestamp(FIXED_NOW));
            job.executeUpdate();
        }
    }

    private void insertBaseMessage(Connection connection, UUID sessionId, long sequence, UUID jobId, String role)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into session_message(session_id, sequence, message_job_id, role, created_at)
                values (?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, sessionId);
            statement.setLong(2, sequence);
            statement.setObject(3, jobId);
            statement.setString(4, role);
            statement.setObject(5, timestamp(FIXED_NOW));
            statement.executeUpdate();
        }
    }

    private void insertToolMessage(Connection connection, UUID sessionId, UUID jobId, long sequence, String modelCallId)
            throws SQLException {
        insertBaseMessage(connection, sessionId, sequence, jobId, "TOOL");
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into tool_message(
                    session_id, sequence, result_id, model_call_id, tool_name, tool_version, tool_kind,
                    arguments_json, result_json, model_context, citeable)
                values (?, ?, ?, ?, ?, ?, 'CATALOG', cast(? as jsonb), cast(? as jsonb), ?, false)
                """)) {
            statement.setObject(1, sessionId);
            statement.setLong(2, sequence);
            statement.setObject(3, UUID.randomUUID());
            statement.setString(4, modelCallId);
            statement.setString(5, "list_repositories");
            statement.setString(6, "v1");
            statement.setString(7, "{}");
            statement.setString(8, "{}");
            statement.setString(9, "AQIDBA==");
            statement.executeUpdate();
        }
    }

    private void insertFeedbackMessage(
            Connection connection,
            UUID sessionId,
            UUID jobId,
            long sequence,
            String modelCallId,
            String toolName,
            String rejectedArgumentsJson,
            String modelContext) throws SQLException {
        insertBaseMessage(connection, sessionId, sequence, jobId, "FEEDBACK");
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into feedback_message(
                    session_id, sequence, code, message, terminal, model_call_id, tool_name, rejected_arguments_json, model_context)
                values (?, ?, ?, ?, false, ?, ?, cast(? as jsonb), ?)
                """)) {
            statement.setObject(1, sessionId);
            statement.setLong(2, sequence);
            statement.setString(3, "TOOL_REJECTED");
            statement.setString(4, "feedback");
            statement.setString(5, modelCallId);
            statement.setString(6, toolName);
            statement.setString(7, rejectedArgumentsJson);
            statement.setString(8, modelContext);
            statement.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SqlWork {

        void execute(Connection connection) throws Exception;
    }
}
