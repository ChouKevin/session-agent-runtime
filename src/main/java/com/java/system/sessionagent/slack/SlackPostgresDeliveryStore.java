package com.java.system.sessionagent.slack;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SlackPostgresDeliveryStore implements SlackDeliveryStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SlackPostgresDeliveryStore(DataSource dataSource) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.jdbcTemplate = new JdbcTemplate(requiredDataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(requiredDataSource));
    }

    @Override
    public void discover() {
        transactionTemplate.executeWithoutResult(status -> discoverInTransaction());
    }

    @Override
    public Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration, int maximumAttempts) {
        Assert.hasText(workerId, "Slack delivery worker ID must not be blank");
        Duration requiredLeaseDuration = Objects.requireNonNull(leaseDuration, "Slack delivery lease duration must not be null");
        Assert.isTrue(!requiredLeaseDuration.isNegative() && !requiredLeaseDuration.isZero(), "Slack delivery lease duration must be positive");
        Assert.isTrue(maximumAttempts > 0, "Slack delivery maximum attempts must be positive");
        SlackDeliveryClaim claim = transactionTemplate.execute(status -> {
            terminalizeExpiredExhaustedDeliveries(maximumAttempts);
            return jdbcTemplate.query("""
                with candidate as (
                    select delivery_id
                    from slack_delivery
                    where attempt_count < ? and ((status in ('PENDING', 'RETRY') and next_attempt_at <= clock_timestamp())
                       or (status = 'WORKING' and locked_until <= clock_timestamp()))
                    order by next_attempt_at, created_at, delivery_id
                    for update skip locked limit 1)
                update slack_delivery delivery set status = 'WORKING', worker_id = ?,
                    locked_until = clock_timestamp() + (? * interval '1 millisecond'),
                    claim_number = delivery.claim_number + 1, attempt_count = delivery.attempt_count + 1
                from candidate where delivery.delivery_id = candidate.delivery_id
                returning delivery.delivery_id, delivery.terminal_session_id, delivery.message_job_id, delivery.claim_number,
                    delivery.attempt_count, delivery.worker_id, delivery.locked_until, delivery.channel_id,
                    delivery.root_thread_ts, delivery.terminal_text
                """, (resultSet, rowNumber) -> new SlackDeliveryClaim(
                resultSet.getObject("delivery_id", UUID.class), resultSet.getObject("terminal_session_id", UUID.class),
                resultSet.getObject("message_job_id", UUID.class), resultSet.getLong("claim_number"),
                resultSet.getInt("attempt_count"), resultSet.getString("worker_id"),
                resultSet.getObject("locked_until", OffsetDateTime.class).toInstant(),
                new SlackPostRequest(resultSet.getString("channel_id"), resultSet.getString("root_thread_ts"),
                        resultSet.getString("terminal_text"))), maximumAttempts, workerId, positiveLeaseMilliseconds(requiredLeaseDuration))
                .stream().findFirst().orElse(null); // cs-allow TransactionTemplate permits nullable no-claim result
        });
        return Optional.ofNullable(claim);
    }

    @Override
    public boolean markSent(SlackDeliveryClaim claim, String slackMessageTs) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        Assert.hasText(slackMessageTs, "Slack message timestamp must not be blank");
        return jdbcTemplate.update("""
                update slack_delivery set status = 'SENT', slack_message_ts = ?, sent_at = clock_timestamp(),
                    worker_id = null, locked_until = null
                where delivery_id = ? and status = 'WORKING' and worker_id = ? and claim_number = ?
                    and locked_until > clock_timestamp()
                """, slackMessageTs, requiredClaim.deliveryId(), requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
    }

    @Override
    public boolean markFailed(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        SlackDeliveryFailureCategory requiredCategory = Objects.requireNonNull(category, "Slack delivery failure category must not be null");
        return jdbcTemplate.update("""
                update slack_delivery set status = 'FAILED', failure_category = ?, worker_id = null, locked_until = null
                where delivery_id = ? and status = 'WORKING' and worker_id = ? and claim_number = ?
                    and locked_until > clock_timestamp()
                """, requiredCategory.name(), requiredClaim.deliveryId(), requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
    }

    @Override
    public boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        SlackDeliveryFailureCategory requiredCategory = Objects.requireNonNull(category, "Slack delivery failure category must not be null");
        Duration requiredDelay = Objects.requireNonNull(delay, "Slack delivery retry delay must not be null");
        Assert.isTrue(!requiredDelay.isNegative(), "Slack delivery retry delay must not be negative");
        return jdbcTemplate.update("""
                update slack_delivery set status = 'RETRY', failure_category = ?,
                    next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'), worker_id = null, locked_until = null
                where delivery_id = ? and status = 'WORKING' and worker_id = ? and claim_number = ?
                    and locked_until > clock_timestamp()
                """, requiredCategory.name(), requiredDelay.toMillis(), requiredClaim.deliveryId(), requiredClaim.workerId(),
                requiredClaim.claimNumber()) == 1;
    }

    @Override
    public Optional<SlackDeliveryView> read(UUID deliveryId) {
        UUID requiredDeliveryId = Objects.requireNonNull(deliveryId, "Slack delivery ID must not be null");
        return jdbcTemplate.query("""
                select delivery_id, status, attempt_count, failure_category, slack_message_ts, next_attempt_at
                from slack_delivery where delivery_id = ?
                """, (resultSet, rowNumber) -> new SlackDeliveryView(resultSet.getObject("delivery_id", UUID.class),
                SlackDeliveryStatus.valueOf(resultSet.getString("status")), resultSet.getInt("attempt_count"),
                Optional.ofNullable(resultSet.getString("failure_category")).map(SlackDeliveryFailureCategory::valueOf),
                Optional.ofNullable(resultSet.getString("slack_message_ts")),
                resultSet.getObject("next_attempt_at", OffsetDateTime.class).toInstant()), requiredDeliveryId).stream().findFirst();
    }

    private void discoverInTransaction() {
        List<NewDelivery> deliveries = jdbcTemplate.query("""
                select job.message_job_id, terminal.session_id, terminal.sequence, binding.channel_id, binding.root_thread_ts,
                    case terminal.role when 'ASSISTANT' then assistant.message else runtime.message end as terminal_text
                from message_job job
                join slack_message_receipt receipt on receipt.message_job_id = job.message_job_id
                    and receipt.session_id = job.session_id and receipt.classification = 'ACCEPTED'
                join slack_thread_binding binding on binding.session_id = job.session_id
                join session_message terminal on terminal.session_id = job.session_id and terminal.message_job_id = job.message_job_id
                left join assistant_message assistant on assistant.session_id = terminal.session_id and assistant.sequence = terminal.sequence
                left join runtime_message runtime on runtime.session_id = terminal.session_id and runtime.sequence = terminal.sequence
                where job.status = 'DONE' and terminal.role in ('ASSISTANT', 'RUNTIME')
                  and terminal.sequence = (select max(later.sequence) from session_message later
                      where later.session_id = job.session_id and later.message_job_id = job.message_job_id)
                  and not exists (select 1 from slack_delivery delivery
                      where delivery.terminal_session_id = terminal.session_id and delivery.terminal_sequence = terminal.sequence)
                """, (resultSet, rowNumber) -> new NewDelivery(resultSet.getObject("message_job_id", UUID.class),
                resultSet.getObject("session_id", UUID.class), resultSet.getLong("sequence"), resultSet.getString("channel_id"),
                resultSet.getString("root_thread_ts"), resultSet.getString("terminal_text")));
        for (NewDelivery delivery : deliveries) {
            jdbcTemplate.update("""
                    insert into slack_delivery(delivery_id, terminal_session_id, terminal_sequence, message_job_id, channel_id,
                        root_thread_ts, terminal_text, status, attempt_count, next_attempt_at, claim_number, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, clock_timestamp(), 0, clock_timestamp())
                    on conflict (terminal_session_id, terminal_sequence) do nothing
                    """, UUID.randomUUID(), delivery.sessionId(), delivery.sequence(), delivery.messageJobId(), delivery.channelId(),
                    delivery.rootThreadTs(), delivery.terminalText());
        }
    }

    private static long positiveLeaseMilliseconds(Duration duration) {
        long milliseconds = duration.toMillis();
        return milliseconds > 0 ? milliseconds : 1;
    }

    private void terminalizeExpiredExhaustedDeliveries(int maximumAttempts) {
        jdbcTemplate.update("""
                update slack_delivery set status = 'FAILED', failure_category = coalesce(failure_category, 'TRANSIENT'),
                    worker_id = null, locked_until = null
                where status = 'WORKING' and locked_until <= clock_timestamp() and attempt_count >= ?
                """, maximumAttempts);
    }

    private record NewDelivery(
            UUID messageJobId,
            UUID sessionId,
            long sequence,
            String channelId,
            String rootThreadTs,
            String terminalText) {
    }
}
