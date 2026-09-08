package com.java.system.sessionagent.slack.adapter.postgres;

import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackDeliveryStatus;
import com.java.system.sessionagent.slack.domain.SlackDeliveryKind;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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
                .map(row -> new SlackDeliveryClaim(row.deliveryId(), SlackDeliveryKind.valueOf(row.kind()),
                        Optional.ofNullable(row.sessionId()), Optional.ofNullable(row.messageJobId()), row.claimNumber(),
                        row.attemptCount(), row.workerId(), row.lockedUntil().toInstant(),
                        new SlackPostRequest(row.channelId(), row.rootThreadTs(), row.terminalText())))
                .findFirst().orElse(null); // cs-allow TransactionTemplate permits nullable no-claim result
        });
        return Optional.ofNullable(claim);
    }

    @Override
    public Optional<SlackDeliveryPart> nextPart(
            SlackDeliveryClaim claim,
            Supplier<List<String>> texts,
            Duration leaseDuration) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        Supplier<List<String>> requiredTexts = Objects.requireNonNull(texts, "Slack delivery texts must not be null");
        Duration requiredLeaseDuration = Objects.requireNonNull(leaseDuration, "Slack delivery lease duration must not be null");
        Assert.isTrue(!requiredLeaseDuration.isNegative() && !requiredLeaseDuration.isZero(), "Slack delivery lease duration must be positive");
        SlackDeliveryPart part = transactionTemplate.execute(status -> {
            if (mapper.renewLease(requiredClaim.deliveryId(), requiredClaim.workerId(), requiredClaim.claimNumber(),
                    positiveLeaseMilliseconds(requiredLeaseDuration)) != 1) {
                return null; // cs-allow TransactionTemplate maps a guarded no-result to null
            }
            if (mapper.countParts(requiredClaim.deliveryId()) == 0) {
                List<String> generatedTexts = requiredTexts.get();
                Assert.notEmpty(generatedTexts, "Slack delivery must contain at least one part");
                mapper.insertParts(requiredClaim.deliveryId(), generatedTexts);
            }
            return mapper.findNextPart(requiredClaim.deliveryId(), requiredClaim.workerId(), requiredClaim.claimNumber()).stream()
                    .findFirst().map(row -> new SlackDeliveryPart(row.partIndex(), row.partText())).orElse(null); // cs-allow TransactionTemplate permits nullable no-part result
        });
        return Optional.ofNullable(part);
    }

    @Override
    public boolean markPartSent(SlackDeliveryClaim claim, SlackDeliveryPart part, String slackMessageTs) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        SlackDeliveryPart requiredPart = Objects.requireNonNull(part, "Slack delivery part must not be null");
        Assert.hasText(slackMessageTs, "Slack message timestamp must not be blank");
        Boolean sent = transactionTemplate.execute(status -> {
            if (mapper.lockOwnedDelivery(requiredClaim.deliveryId(), requiredClaim.workerId(), requiredClaim.claimNumber()).isEmpty()) {
                return false;
            }
            if (mapper.markPartSent(slackMessageTs, requiredClaim.deliveryId(), requiredPart.index(), requiredClaim.workerId(),
                    requiredClaim.claimNumber()) != 1) {
                return false;
            }
            if (mapper.countUnsentParts(requiredClaim.deliveryId()) == 0
                    && mapper.markDeliverySent(slackMessageTs, requiredClaim.deliveryId(), requiredClaim.workerId(),
                    requiredClaim.claimNumber()) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(sent);
    }

    @Override
    public boolean markPartFailed(
            SlackDeliveryClaim claim,
            SlackDeliveryPart part,
            SlackDeliveryFailureCategory category,
            Optional<String> slackMessageTs) {
        SlackDeliveryClaim requiredClaim = Objects.requireNonNull(claim, "Slack delivery claim must not be null");
        SlackDeliveryPart requiredPart = Objects.requireNonNull(part, "Slack delivery part must not be null");
        SlackDeliveryFailureCategory requiredCategory = Objects.requireNonNull(category, "Slack delivery failure category must not be null");
        Optional<String> requiredSlackMessageTs = Objects.requireNonNull(slackMessageTs, "Slack message timestamp must not be null");
        Boolean failed = transactionTemplate.execute(status -> {
            String timestamp = requiredSlackMessageTs.orElse(null); // cs-allow JDBC binds an absent known Slack timestamp as SQL null
            if (mapper.markPartFailed(timestamp, requiredClaim.deliveryId(), requiredPart.index(), requiredClaim.workerId(),
                    requiredClaim.claimNumber()) != 1) {
                return false;
            }
            if (mapper.markDeliveryFailed(requiredCategory.name(), requiredClaim.deliveryId(), requiredClaim.workerId(),
                    requiredClaim.claimNumber()) != 1) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(failed);
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
        return mapper.findDelivery(requiredDeliveryId).stream().findFirst().map(this::view);
    }

    @Override
    public List<SlackDeliveryView> findBySource(String teamId, String channelId, String messageTs) {
        Assert.hasText(teamId, "Slack team ID must not be blank");
        Assert.hasText(channelId, "Slack channel ID must not be blank");
        Assert.hasText(messageTs, "Slack message timestamp must not be blank");
        return mapper.findDeliveriesBySource(teamId, channelId, messageTs).stream().map(this::view).toList();
    }

    private SlackDeliveryView view(SlackDatabaseMapper.DeliveryViewRow row) {
        return new SlackDeliveryView(row.deliveryId(), SlackDeliveryKind.valueOf(row.kind()), SlackDeliveryStatus.valueOf(row.status()),
                row.attemptCount(), Optional.ofNullable(row.failureCategory()).map(SlackDeliveryFailureCategory::valueOf),
                Optional.ofNullable(row.slackMessageTs()), row.nextAttemptAt().toInstant(), Optional.ofNullable(row.messageJobId()),
                row.totalParts() == 0 ? Optional.empty() : Optional.of(row.totalParts()), row.sentParts());
    }

    private void discoverInTransaction() {
        List<SlackDatabaseMapper.NewDeliveryRow> deliveries = mapper.discoverDeliveries();
        for (SlackDatabaseMapper.NewDeliveryRow delivery : deliveries) {
            mapper.insertDelivery(UUID.randomUUID(), delivery.sessionId(), delivery.sequence(), delivery.messageJobId(),
                    delivery.teamId(), delivery.channelId(), delivery.rootThreadTs(), delivery.messageTs(), delivery.terminalText());
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
