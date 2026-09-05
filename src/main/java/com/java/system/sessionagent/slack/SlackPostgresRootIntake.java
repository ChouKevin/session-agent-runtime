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
    public SlackEventOutcome receive(SlackRootIntake intake) {
        SlackRootIntake requiredIntake = Objects.requireNonNull(intake, "Slack intake must not be null");
        SlackEventOutcome outcome = transactionTemplate.execute(status -> receiveInTransaction(requiredIntake));
        return Objects.requireNonNull(outcome, "Slack intake outcome must not be null");
    }

    private SlackEventOutcome receiveInTransaction(SlackRootIntake intake) {
        Optional<StoredEventReceipt> existingEvent = findEventReceipt(intake.eventId());
        if (existingEvent.isPresent()) {
            return verifyExistingEvent(intake, existingEvent.orElseThrow());
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

    private SlackEventOutcome acceptRoot(SlackRootIntake intake) {
        MessageReceipt receipt = receiveMessage(intake);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        jdbcTemplate.update("""
                insert into slack_thread_binding(team_id, channel_id, root_thread_ts, session_id, created_at)
                values (?, ?, ?, ?, ?)
                on conflict (team_id, channel_id, root_thread_ts) do nothing
                """, intake.teamId(), intake.channelId(), intake.rootThreadTs(), sessionId, createdAt());
        Optional<UUID> binding = findThreadBinding(intake);
        Assert.isTrue(binding.isPresent() && binding.orElseThrow().equals(sessionId), "Slack thread binding must be immutable");
        return finishAccepted(intake, receipt);
    }

    private SlackEventOutcome accept(SlackRootIntake intake, UUID bindingSessionId) {
        MessageReceipt receipt = receiveMessage(intake);
        Assert.isTrue(bindingSessionId.toString().equals(receipt.sessionId().value()),
                "Slack reply must use its immutable thread session binding");
        return finishAccepted(intake, receipt);
    }

    private SlackEventOutcome finishAccepted(SlackRootIntake intake, MessageReceipt receipt) {
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        jdbcTemplate.update("""
                insert into slack_message_receipt(team_id, channel_id, message_ts, session_id, message_job_id, created_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (team_id, channel_id, message_ts) do nothing
                """, intake.teamId(), intake.channelId(), intake.messageTs(), sessionId, jobId, createdAt());
        Optional<StoredMessageReceipt> messageReceipt = findMessageReceipt(intake);
        Assert.isTrue(messageReceipt.isPresent() && messageReceipt.orElseThrow().matches(sessionId, jobId),
                "Slack logical message receipt must match its committed outcome");
        persistEventReceipt(intake, jobId);
        Optional<StoredEventReceipt> eventReceipt = findEventReceipt(intake.eventId());
        Assert.isTrue(eventReceipt.isPresent() && eventReceipt.orElseThrow().matches(intake, jobId),
                "Slack event receipt must match its committed outcome");
        return SlackEventOutcome.ACCEPTED;
    }

    private SlackEventOutcome verifyExistingEvent(SlackRootIntake intake, StoredEventReceipt existing) {
        Assert.isTrue(existing.matches(intake, existing.correlationId()), "Slack event ID must retain one logical outcome");
        if (intake.classification() != SlackIntakeClassification.ACCEPTED) {
            return SlackEventOutcome.IGNORED;
        }
        MessageReceipt receipt = receiveMessage(intake);
        UUID jobId = UUID.fromString(receipt.messageJobId().value());
        Assert.isTrue(existing.correlationId().equals(jobId), "Slack event duplicate must retain its message job");
        return SlackEventOutcome.ACCEPTED;
    }

    private MessageReceipt receiveMessage(SlackRootIntake intake) {
        IncomingMessage message = intake.message().orElseThrow(MessageConflictException::new);
        return messageIntakePort.receive(message);
    }

    private SlackEventOutcome ignore(SlackRootIntake intake) {
        persistEventReceipt(intake, null);
        Optional<StoredEventReceipt> eventReceipt = findEventReceipt(intake.eventId());
        Assert.isTrue(eventReceipt.isPresent() && eventReceipt.orElseThrow().matches(intake, null),
                "Slack ignored event receipt must match its committed outcome");
        return SlackEventOutcome.IGNORED;
    }

    private void persistEventReceipt(SlackRootIntake intake, UUID correlationId) {
        jdbcTemplate.update("""
                insert into slack_event_receipt(event_id, team_id, channel_id, message_ts, classification, correlation_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (event_id) do nothing
                """, intake.eventId(), intake.teamId(), intake.channelId(), intake.messageTs(), intake.classification().name(),
                correlationId, createdAt());
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
                select session_id, message_job_id from slack_message_receipt
                where team_id = ? and channel_id = ? and message_ts = ?
                """, (resultSet, rowNumber) -> new StoredMessageReceipt(resultSet.getObject("session_id", UUID.class),
                resultSet.getObject("message_job_id", UUID.class)), intake.teamId(), intake.channelId(), intake.messageTs())
                .stream().findFirst();
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

        private boolean matches(SlackRootIntake intake, UUID expectedCorrelationId) {
            return teamId.equals(intake.teamId()) && channelId.equals(intake.channelId())
                    && messageTs.equals(intake.messageTs()) && classification == intake.classification()
                    && Objects.equals(correlationId, expectedCorrelationId);
        }
    }

    private record StoredMessageReceipt(UUID sessionId, UUID messageJobId) {

        private boolean matches(UUID expectedSessionId, UUID expectedJobId) {
            return sessionId.equals(expectedSessionId) && messageJobId.equals(expectedJobId);
        }
    }
}
