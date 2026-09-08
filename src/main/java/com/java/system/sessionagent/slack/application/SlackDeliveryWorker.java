package com.java.system.sessionagent.slack.application;

import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackTextParts;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;

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
        deliverParts(currentClaim);
        return true;
    }

    private void deliverParts(SlackDeliveryClaim claim) {
        Optional<SlackDeliveryPart> nextPart;
        try {
            nextPart = deliveryStore.nextPart(claim, () -> SlackTextParts.split(claim.postRequest().text()), properties.leaseDuration());
        } catch (RuntimeException exception) {
            logDelivery("slack_delivery_ownership_lost", claim, "PARTS_NOT_PREPARED", Optional.empty());
            return;
        }
        while (nextPart.isPresent()) {
            SlackDeliveryPart part = nextPart.orElseThrow();
            SlackPostRequest request = new SlackPostRequest(claim.postRequest().channelId(), claim.postRequest().rootThreadTs(), part.text());
            try {
                String slackMessageTs = slackWebApi.post(request);
                boolean sentCommitted = deliveryStore.markPartSent(claim, part, slackMessageTs);
                logDelivery(sentCommitted ? "slack_delivery_sent" : "slack_delivery_ambiguous", claim,
                        sentCommitted ? "SENT" : "POSTED_STATE_NOT_COMMITTED", Optional.empty());
                if (!sentCommitted) {
                    return;
                }
                nextPart = deliveryStore.nextPart(claim, () -> SlackTextParts.split(claim.postRequest().text()),
                        properties.leaseDuration());
            } catch (SlackPostFailure failure) {
                recordFailure(claim, part, failure.category(), failure.retryAfter(), failure.slackMessageTs());
                return;
            } catch (RuntimeException exception) {
                recordFailure(claim, part, SlackDeliveryFailureCategory.TRANSIENT, Optional.empty(), Optional.empty());
                return;
            }
        }
    }

    private void recordFailure(
            SlackDeliveryClaim claim,
            SlackDeliveryPart part,
            SlackDeliveryFailureCategory category,
            Optional<Duration> retryAfter,
            Optional<String> slackMessageTs) {
        if (category == SlackDeliveryFailureCategory.PERMANENT || claim.attemptCount() >= properties.maximumAttempts()) {
            boolean failedCommitted = deliveryStore.markPartFailed(claim, part, category, slackMessageTs);
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
                .addKeyValue("kind", claim.kind().name())
                .addKeyValue("slackChannelId", claim.postRequest().channelId())
                .addKeyValue("slackThreadTs", claim.postRequest().rootThreadTs())
                .addKeyValue("attempt", claim.attemptCount())
                .addKeyValue("outcome", outcome);
        claim.sessionId().ifPresent(sessionId -> builder.addKeyValue("sessionId", sessionId.toString()));
        claim.messageJobId().ifPresent(messageJobId -> builder.addKeyValue("messageJobId", messageJobId.toString()));
        retryDelay.ifPresent(delay -> builder.addKeyValue("retryDelayMs", delay.toMillis()));
        builder.log("runtime_lifecycle");
    }
}
