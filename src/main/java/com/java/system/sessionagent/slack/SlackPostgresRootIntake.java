package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.port.in.MessageConflictException;
import com.java.system.sessionagent.conversation.port.in.MessageIntakePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SlackPostgresRootIntake implements SlackRootIntakePort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MessageIntakePort messageIntakePort;
    private final Clock clock;

    public SlackPostgresRootIntake(DataSource dataSource, MessageIntakePort messageIntakePort, Clock clock) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.jdbcTemplate = new JdbcTemplate(requiredDataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(requiredDataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.messageIntakePort = Objects.requireNonNull(messageIntakePort, "Message intake port must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public SlackIntakeResult receive(SlackRootIntake intake) {
        SlackRootIntake requiredIntake = Objects.requireNonNull(intake, "Slack intake must not be null");
        try {
            return receiveInRequiredTransaction(requiredIntake);
        } catch (CommittedLogicalOutcomeRaceException exception) {
            return receiveInRequiredTransaction(requiredIntake);
        }
    }

    private SlackIntakeResult receiveInRequiredTransaction(SlackRootIntake intake) {
        SlackIntakeResult result = transactionTemplate.execute(status -> receiveInTransaction(intake));
        return Objects.requireNonNull(result, "Slack intake result must not be null");
    }

    private SlackIntakeResult receiveInTransaction(SlackRootIntake intake) {
        Optional<StoredEventReceipt> existingEvent = findEventReceipt(intake.eventId());
        if (existingEvent.isPresent()) {
            return verifyExistingEvent(intake, existingEvent.orElseThrow());
        }
        acquireLogicalMessageLock(intake);
        Optional<StoredMessageReceipt> existingLogicalMessage = findMessageReceipt(intake);
        if (existingLogicalMessage.isPresent() && !canAcceptTopLevelRootAfterIgnored(intake, existingLogicalMessage.orElseThrow())) {
            return replayLogicalMessage(intake, existingLogicalMessage.orElseThrow());
        }
        if (intake.classification() != SlackIntakeClassification.ACCEPTED) {
            return ignore(intake);
        }
        if (isThreadReply(intake)) {
            Optional<UUID> binding = findThreadBinding(intake);
            if (binding.isEmpty()) {
                SlackRootIntake ignoredIntake = new SlackRootIntake(intake.eventId(), intake.teamId(), intake.channelId(),
                        intake.rootThreadTs(), intake.messageTs(), SlackIntakeClassification.UNBOUND_THREAD, Optional.empty());
                return ignore(ignoredIntake);
            }
            return accept(intake, binding.orElseThrow());
        }
        return acceptRoot(intake);
    }

    private SlackIntakeResult acceptRoot(SlackRootIntake intake) {
        MessageReceipt receipt = receiveMessage(intake);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        jdbcTemplate.update("""
                insert into slack_thread_binding(team_id, channel_id, root_thread_ts, session_id, created_at)
                values (?, ?, ?, ?, ?)
                on conflict (team_id, channel_id, root_thread_ts) do nothing
                """, intake.teamId(), intake.channelId(), intake.rootThreadTs(), sessionId, createdAt());
        Optional<UUID> binding = findThreadBinding(intake);
        Assert.isTrue(binding.isPresent() && binding.orElseThrow().equals(sessionId), "Slack thread binding must be immutable");
        return finishAccepted(intake, receipt, SlackSessionResolution.CREATED);
    }

    private SlackIntakeResult accept(SlackRootIntake intake, UUID bindingSessionId) {
        MessageReceipt receipt = receiveMessage(intake);
        Assert.isTrue(bindingSessionId.toString().equals(receipt.sessionId().value()),
                "Slack reply must use its immutable thread session binding");
        return finishAccepted(intake, receipt, SlackSessionResolution.RESOLVED);
    }

    private SlackIntakeResult finishAccepted(
            SlackRootIntake intake,
            MessageReceipt receipt,
            SlackSessionResolution sessionResolution) {
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        boolean newLogicalMessage = persistLogicalReceipt(
                intake, SlackIntakeClassification.ACCEPTED, sessionId, jobId);
        persistAndVerifyEventReceipt(intake, SlackIntakeClassification.ACCEPTED, jobId);
        if (!newLogicalMessage) {
            return SlackIntakeResult.duplicateAccepted(sessionId, jobId);
        }
        return sessionResolution == SlackSessionResolution.CREATED
                ? SlackIntakeResult.newSessionAccepted(sessionId, jobId)
                : SlackIntakeResult.resolvedSessionAccepted(sessionId, jobId);
    }

    private SlackIntakeResult verifyExistingEvent(SlackRootIntake intake, StoredEventReceipt existing) {
        Assert.isTrue(existing.matchesEnvelope(intake), "Slack event ID must retain one logical identity");
        if (!existing.isAccepted()) {
            return SlackIntakeResult.duplicateIgnored();
        }
        if (intake.classification() != SlackIntakeClassification.ACCEPTED) {
            return duplicateAccepted(intake);
        }
        MessageReceipt receipt = receiveMessage(intake);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        Assert.isTrue(existing.matchesOutcome(SlackIntakeClassification.ACCEPTED, jobId),
                "Slack event duplicate must retain its message job");
        return SlackIntakeResult.duplicateAccepted(sessionId, jobId);
    }

    private SlackIntakeResult replayLogicalMessage(SlackRootIntake intake, StoredMessageReceipt existing) {
        if (!existing.isAccepted()) {
            persistAndVerifyEventReceipt(intake, existing.classification(), null);
            return SlackIntakeResult.duplicateIgnored();
        }
        if (intake.classification() != SlackIntakeClassification.ACCEPTED) {
            persistAndVerifyEventReceipt(intake, SlackIntakeClassification.ACCEPTED, existing.messageJobId());
            return SlackIntakeResult.duplicateAccepted(existing.sessionId(), existing.messageJobId());
        }
        MessageReceipt receipt = receiveMessage(intake);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        Assert.isTrue(existing.matches(SlackIntakeClassification.ACCEPTED, sessionId, jobId),
                "Slack logical message receipt must retain its committed outcome");
        persistAndVerifyEventReceipt(intake, SlackIntakeClassification.ACCEPTED, jobId);
        return SlackIntakeResult.duplicateAccepted(sessionId, jobId);
    }

    private MessageReceipt receiveMessage(SlackRootIntake intake) {
        IncomingMessage message = intake.message().orElseThrow(MessageConflictException::new);
        return messageIntakePort.receive(message);
    }

    private SlackIntakeResult ignore(SlackRootIntake intake) {
        boolean newLogicalMessage = persistLogicalReceipt(intake, intake.classification(), null, null);
        persistAndVerifyEventReceipt(intake, intake.classification(), null);
        return newLogicalMessage ? SlackIntakeResult.newIgnored() : SlackIntakeResult.duplicateIgnored();
    }

    private SlackIntakeResult duplicateAccepted(SlackRootIntake intake) {
        StoredMessageReceipt receipt = findMessageReceipt(intake).orElseThrow();
        Assert.isTrue(receipt.isAccepted(), "Accepted Slack event must retain its logical message receipt");
        return SlackIntakeResult.duplicateAccepted(receipt.sessionId(), receipt.messageJobId());
    }

    private boolean persistLogicalReceipt(
            SlackRootIntake intake,
            SlackIntakeClassification classification,
            UUID sessionId,
            UUID jobId) {
        int insertedRows = jdbcTemplate.update("""
                insert into slack_message_receipt(team_id, channel_id, message_ts, top_level, classification, session_id, message_job_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (team_id, channel_id, message_ts) do update
                set classification = excluded.classification,
                    session_id = excluded.session_id,
                    message_job_id = excluded.message_job_id
                where slack_message_receipt.top_level
                    and slack_message_receipt.classification <> 'ACCEPTED'
                    and excluded.top_level
                    and excluded.classification = 'ACCEPTED'
                """, intake.teamId(), intake.channelId(), intake.messageTs(), !isThreadReply(intake), classification.name(), sessionId, jobId,
                createdAt());
        Optional<StoredMessageReceipt> messageReceipt = findMessageReceipt(intake);
        Assert.isTrue(messageReceipt.isPresent(), "Slack logical message receipt must be committed");
        if (!messageReceipt.orElseThrow().matches(classification, sessionId, jobId)) {
            throw new CommittedLogicalOutcomeRaceException();
        }
        return insertedRows == 1;
    }

    private void persistAndVerifyEventReceipt(
            SlackRootIntake intake,
            SlackIntakeClassification classification,
            UUID correlationId) {
        jdbcTemplate.update("""
                insert into slack_event_receipt(event_id, team_id, channel_id, message_ts, classification, correlation_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (event_id) do nothing
                """, intake.eventId(), intake.teamId(), intake.channelId(), intake.messageTs(), classification.name(),
                correlationId, createdAt());
        Optional<StoredEventReceipt> eventReceipt = findEventReceipt(intake.eventId());
        Assert.isTrue(eventReceipt.isPresent() && eventReceipt.orElseThrow().matchesEnvelope(intake)
                        && eventReceipt.orElseThrow().matchesOutcome(classification, correlationId),
                "Slack event receipt must match its committed outcome");
    }

    private Optional<UUID> findThreadBinding(SlackRootIntake intake) {
        return jdbcTemplate.query("""
                select session_id from slack_thread_binding
                where team_id = ? and channel_id = ? and root_thread_ts = ?
                """, (resultSet, rowNumber) -> resultSet.getObject("session_id", UUID.class), intake.teamId(), intake.channelId(),
                intake.rootThreadTs()).stream().findFirst();
    }

    private Optional<StoredEventReceipt> findEventReceipt(String eventId) {
        return jdbcTemplate.query("""
                select team_id, channel_id, message_ts, classification, correlation_id
                from slack_event_receipt where event_id = ?
                """, (resultSet, rowNumber) -> new StoredEventReceipt(resultSet.getString("team_id"),
                resultSet.getString("channel_id"), resultSet.getString("message_ts"),
                SlackIntakeClassification.valueOf(resultSet.getString("classification")),
                resultSet.getObject("correlation_id", UUID.class)), eventId).stream().findFirst();
    }

    private Optional<StoredMessageReceipt> findMessageReceipt(SlackRootIntake intake) {
        return jdbcTemplate.query("""
                select top_level, classification, session_id, message_job_id from slack_message_receipt
                where team_id = ? and channel_id = ? and message_ts = ?
                """, (resultSet, rowNumber) -> new StoredMessageReceipt(
                resultSet.getBoolean("top_level"), SlackIntakeClassification.valueOf(resultSet.getString("classification")),
                resultSet.getObject("session_id", UUID.class),
                resultSet.getObject("message_job_id", UUID.class)), intake.teamId(), intake.channelId(), intake.messageTs())
                .stream().findFirst();
    }

    private static boolean canAcceptTopLevelRootAfterIgnored(SlackRootIntake intake, StoredMessageReceipt existing) {
        return intake.classification() == SlackIntakeClassification.ACCEPTED
                && !isThreadReply(intake)
                && existing.isIgnoredTopLevel();
    }

    private void acquireLogicalMessageLock(SlackRootIntake intake) {
        jdbcTemplate.query("""
                select pg_advisory_xact_lock(hashtextextended(json_build_array(?, ?, ?)::text, 0))
                """, resultSet -> { }, intake.teamId(), intake.channelId(), intake.messageTs());
    }

    private OffsetDateTime createdAt() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static boolean isThreadReply(SlackRootIntake intake) {
        return !intake.rootThreadTs().equals(intake.messageTs());
    }

    private record StoredEventReceipt(
            String teamId,
            String channelId,
            String messageTs,
            SlackIntakeClassification classification,
            UUID correlationId) {

        private boolean matchesEnvelope(SlackRootIntake intake) {
            return teamId.equals(intake.teamId()) && channelId.equals(intake.channelId())
                    && messageTs.equals(intake.messageTs());
        }

        private boolean isAccepted() {
            return classification == SlackIntakeClassification.ACCEPTED;
        }

        private boolean matchesOutcome(SlackIntakeClassification expectedClassification, UUID expectedCorrelationId) {
            return classification == expectedClassification && Objects.equals(correlationId, expectedCorrelationId);
        }
    }

    private record StoredMessageReceipt(boolean topLevel, SlackIntakeClassification classification, UUID sessionId, UUID messageJobId) {

        private boolean isAccepted() {
            return classification == SlackIntakeClassification.ACCEPTED;
        }

        private boolean isIgnoredTopLevel() {
            return topLevel && !isAccepted();
        }

        private boolean matches(SlackIntakeClassification expectedClassification, UUID expectedSessionId, UUID expectedJobId) {
            return classification == expectedClassification && Objects.equals(sessionId, expectedSessionId)
                    && Objects.equals(messageJobId, expectedJobId);
        }
    }

    private static final class CommittedLogicalOutcomeRaceException extends RuntimeException {
    }
}
