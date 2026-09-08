package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface SlackDeliveryStore {

    void discover();

    Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration, int maximumAttempts);

    Optional<SlackDeliveryPart> nextPart(
            SlackDeliveryClaim claim,
            Supplier<List<String>> texts,
            Duration leaseDuration);

    boolean markPartSent(SlackDeliveryClaim claim, SlackDeliveryPart part, String slackMessageTs);

    boolean markPartFailed(
            SlackDeliveryClaim claim,
            SlackDeliveryPart part,
            SlackDeliveryFailureCategory category,
            Optional<String> slackMessageTs);

    boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay);

    Optional<SlackDeliveryView> read(UUID deliveryId);

    List<SlackDeliveryView> findBySource(String teamId, String channelId, String messageTs);
}
