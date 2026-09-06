package com.java.system.sessionagent.slack;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface SlackDeliveryStore {

    void discover();

    Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration);

    boolean markSent(SlackDeliveryClaim claim, String slackMessageTs);

    boolean markFailed(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category);

    boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay);

    Optional<SlackDeliveryView> read(UUID deliveryId);
}
