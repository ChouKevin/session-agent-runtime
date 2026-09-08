package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlackDeliveryStore {

    void discover();

    Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration, int maximumAttempts);

    boolean markSent(SlackDeliveryClaim claim, String slackMessageTs);

    boolean markFailed(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category);

    boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay);

    Optional<SlackDeliveryView> read(UUID deliveryId);

    List<SlackDeliveryView> findBySource(String teamId, String channelId, String messageTs);
}
