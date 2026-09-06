package com.java.system.sessionagent.slack;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SlackPostgresDeliveryStore implements SlackDeliveryStore {

    private final SlackDatabaseMapper mapper;
    private final TransactionTemplate transactionTemplate;

    public SlackPostgresDeliveryStore(DataSource dataSource) {
        this(SlackMybatisMapperFactory.create(dataSource), dataSource);
    }

    public SlackPostgresDeliveryStore(SlackDatabaseMapper mapper, DataSource dataSource) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "Data source must not be null");
        this.mapper = Objects.requireNonNull(mapper, "Slack database mapper must not be null");
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
            return mapper.claimNext(maximumAttempts, workerId, positiveLeaseMilliseconds(requiredLeaseDuration)).stream()
                .map(row -> new SlackDeliveryClaim(row.deliveryId(), row.sessionId(), row.messageJobId(), row.claimNumber(),
                        row.attemptCount(), row.workerId(), row.lockedUntil().toInstant(),
                        new SlackPostRequest(row.channelId(), row.rootThreadTs(), row.terminalText())))
                .findFirst().orElse(null); // cs-allow TransactionTemplate permits nullable no-claim result
        });
        return Optional.ofNullable(claim);
    }

    @Override
    public boolean markSent(SlackDeliveryClaim claim, String slackMessageTs) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        Assert.hasText(slackMessageTs, "Slack message timestamp must not be blank");
        return mapper.markSent(slackMessageTs, requiredClaim.deliveryId(), requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
    }

    @Override
    public boolean markFailed(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        SlackDeliveryFailureCategory requiredCategory = Objects.requireNonNull(category, "Slack delivery failure category must not be null");
        return mapper.markFailed(requiredCategory.name(), requiredClaim.deliveryId(), requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
    }

    @Override
    public boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        SlackDeliveryFailureCategory requiredCategory = Objects.requireNonNull(category, "Slack delivery failure category must not be null");
        Duration requiredDelay = Objects.requireNonNull(delay, "Slack delivery retry delay must not be null");
        Assert.isTrue(!requiredDelay.isNegative(), "Slack delivery retry delay must not be negative");
        return mapper.scheduleRetry(requiredCategory.name(), requiredDelay.toMillis(), requiredClaim.deliveryId(),
                requiredClaim.workerId(), requiredClaim.claimNumber()) == 1;
    }

    @Override
    public Optional<SlackDeliveryView> read(UUID deliveryId) {
        UUID requiredDeliveryId = Objects.requireNonNull(deliveryId, "Slack delivery ID must not be null");
        SlackDatabaseMapper.DeliveryEntity delivery = mapper.selectById(requiredDeliveryId);
        return Optional.ofNullable(delivery).map(value -> new SlackDeliveryView(value.getDeliveryId(),
                SlackDeliveryStatus.valueOf(value.getStatus()), value.getAttemptCount(),
                Optional.ofNullable(value.getFailureCategory()).map(SlackDeliveryFailureCategory::valueOf),
                Optional.ofNullable(value.getSlackMessageTs()), value.getNextAttemptAt().toInstant()));
    }

    private void discoverInTransaction() {
        List<SlackDatabaseMapper.NewDeliveryRow> deliveries = mapper.discoverDeliveries();
        for (SlackDatabaseMapper.NewDeliveryRow delivery : deliveries) {
            mapper.insertDelivery(UUID.randomUUID(), delivery.sessionId(), delivery.sequence(), delivery.messageJobId(),
                    delivery.channelId(), delivery.rootThreadTs(), delivery.terminalText());
        }
    }

    private static long positiveLeaseMilliseconds(Duration duration) {
        long milliseconds = duration.toMillis();
        return milliseconds > 0 ? milliseconds : 1;
    }

    private void terminalizeExpiredExhaustedDeliveries(int maximumAttempts) {
        mapper.terminalizeExpiredExhaustedDeliveries(maximumAttempts);
    }
}
