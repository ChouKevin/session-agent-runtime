package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.MessageReceipt;
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
import java.util.UUID;

public final class SlackPostgresRootIntake implements SlackRootIntakePort {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MessageIntakePort messageIntakePort;
    private final Clock clock;

    public SlackPostgresRootIntake(
            DataSource dataSource,
            MessageIntakePort messageIntakePort,
            Clock clock) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.jdbcTemplate = new JdbcTemplate(requiredDataSource);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(requiredDataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.messageIntakePort = Objects.requireNonNull(messageIntakePort, "Message intake port must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    public MessageReceipt receive(SlackRootIntake intake) {
        SlackRootIntake requiredIntake = Objects.requireNonNull(intake, "Slack root intake must not be null");
        MessageReceipt receipt = transactionTemplate.execute(status -> receiveInTransaction(requiredIntake));
        return Objects.requireNonNull(receipt, "Slack intake receipt must not be null");
    }

    private MessageReceipt receiveInTransaction(SlackRootIntake intake) {
        MessageReceipt receipt = messageIntakePort.receive(intake.message());
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        UUID sessionId = UUID.fromString(receipt.sessionId().value());
        jdbcTemplate.update("""
                insert into slack_thread_binding(team_id, channel_id, root_thread_ts, session_id, created_at)
                values (?, ?, ?, ?, ?)
                on conflict (team_id, channel_id, root_thread_ts) do nothing
                """, intake.teamId(), intake.channelId(), intake.rootThreadTs(), sessionId, createdAt);
        jdbcTemplate.update("""
                insert into slack_event_receipt(team_id, channel_id, message_ts, session_id, created_at)
                values (?, ?, ?, ?, ?)
                on conflict (team_id, channel_id, message_ts) do nothing
                """, intake.teamId(), intake.channelId(), intake.messageTs(), sessionId, createdAt);
        return receipt;
    }
}
