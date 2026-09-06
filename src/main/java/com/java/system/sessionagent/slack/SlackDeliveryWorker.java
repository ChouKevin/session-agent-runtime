package com.java.system.sessionagent.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class SlackDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackDeliveryWorker.class);
    private final SlackDeliveryStore deliveryStore;
    private final SlackWebApi slackWebApi;
    private final SlackDeliveryProperties properties;
    private final String workerId;

    public SlackDeliveryWorker(
            SlackDeliveryStore deliveryStore,
            SlackWebApi slackWebApi,
            SlackDeliveryProperties properties,
            String workerId) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "Slack delivery store must not be null");
        this.slackWebApi = Objects.requireNonNull(slackWebApi, "Slack Web API must not be null");
        this.properties = Objects.requireNonNull(properties, "Slack delivery properties must not be null");
        Assert.hasText(workerId, "Slack delivery worker ID must not be blank");
        this.workerId = workerId;
    }

    public boolean poll() {
        deliveryStore.discover();
        LOGGER.atInfo().addKeyValue("event", "slack_delivery_recovery")
                .addKeyValue("component", "SLACK_DELIVERY").addKeyValue("outcome", "SCANNED").log("runtime_lifecycle");
        Optional<SlackDeliveryClaim> claim = deliveryStore.claimNext(workerId, properties.leaseDuration(), properties.maximumAttempts());
        if (claim.isEmpty()) {
            return false;
        }
        SlackDeliveryClaim currentClaim = claim.orElseThrow();
        logDelivery("slack_delivery_attempt", currentClaim, "ATTEMPT", Optional.empty());
        try {
            String slackMessageTs = slackWebApi.post(currentClaim.postRequest());
            boolean sentCommitted = deliveryStore.markSent(currentClaim, slackMessageTs);
            logDelivery(sentCommitted ? "slack_delivery_sent" : "slack_delivery_ambiguous", currentClaim,
                    sentCommitted ? "SENT" : "POSTED_STATE_NOT_COMMITTED", Optional.empty());
        } catch (SlackPostFailure failure) {
            recordFailure(currentClaim, failure.category(), failure.retryAfter());
        } catch (RuntimeException exception) {
            recordFailure(currentClaim, SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
        }
        return true;
    }

    private void recordFailure(
            SlackDeliveryClaim claim,
            SlackDeliveryFailureCategory category,
            Optional<Duration> retryAfter) {
        if (category == SlackDeliveryFailureCategory.PERMANENT || claim.attemptCount() >= properties.maximumAttempts()) {
            boolean failedCommitted = deliveryStore.markFailed(claim, category);
            logDelivery(failedCommitted ? "slack_delivery_failed" : "slack_delivery_ownership_lost", claim,
                    failedCommitted ? category.name() : "FAILED_STATE_NOT_COMMITTED", Optional.empty());
            return;
        }
        Duration delay = category == SlackDeliveryFailureCategory.RATE_LIMIT
                ? retryAfter.orElseThrow()
                : exponentialBackoff(claim.attemptCount());
        boolean retryCommitted = deliveryStore.scheduleRetry(claim, category, delay);
        logDelivery(retryCommitted ? "slack_delivery_retry" : "slack_delivery_ownership_lost", claim,
                retryCommitted ? category.name() : "RETRY_STATE_NOT_COMMITTED", Optional.of(delay));
    }

    private Duration exponentialBackoff(int attemptCount) {
        Duration delay = properties.initialBackoff();
        for (int index = 1; index < attemptCount && delay.compareTo(properties.maximumBackoff()) < 0; index++) {
            if (delay.compareTo(properties.maximumBackoff().dividedBy(2)) > 0) {
                return properties.maximumBackoff();
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(properties.maximumBackoff()) > 0 ? properties.maximumBackoff() : delay;
    }

    private static void logDelivery(
            String event,
            SlackDeliveryClaim claim,
            String outcome,
            Optional<Duration> retryDelay) {
        LoggingEventBuilder builder = LOGGER.atInfo().addKeyValue("event", event)
                .addKeyValue("deliveryId", claim.deliveryId().toString())
                .addKeyValue("sessionId", claim.sessionId().toString())
                .addKeyValue("messageJobId", claim.messageJobId().toString())
                .addKeyValue("slackChannelId", claim.postRequest().channelId())
                .addKeyValue("slackThreadTs", claim.postRequest().rootThreadTs())
                .addKeyValue("attempt", claim.attemptCount())
                .addKeyValue("outcome", outcome);
        retryDelay.ifPresent(delay -> builder.addKeyValue("retryDelayMs", delay.toMillis()));
        builder.log("runtime_lifecycle");
    }
}
