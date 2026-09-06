package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class SlackDeliveryWorker {

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
        Optional<SlackDeliveryClaim> claim = deliveryStore.claimNext(workerId, properties.leaseDuration(), properties.maximumAttempts());
        if (claim.isEmpty()) {
            return false;
        }
        SlackDeliveryClaim currentClaim = claim.orElseThrow();
        try {
            String slackMessageTs = slackWebApi.post(currentClaim.postRequest());
            deliveryStore.markSent(currentClaim, slackMessageTs);
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
            deliveryStore.markFailed(claim, category);
            return;
        }
        Duration delay = category == SlackDeliveryFailureCategory.RATE_LIMIT
                ? retryAfter.orElseThrow()
                : exponentialBackoff(claim.attemptCount());
        deliveryStore.scheduleRetry(claim, category, delay);
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
}
