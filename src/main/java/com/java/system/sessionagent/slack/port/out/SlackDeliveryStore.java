package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackDeliveryView;
import com.java.system.sessionagent.slack.domain.SlackUploadTicket;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlackDeliveryStore {

    void discover();

    Optional<SlackDeliveryClaim> claimNext(String workerId, Duration leaseDuration, int maximumAttempts);

    /** Atomically prepares the full required item set once, then renews ownership for the next operation. */
    Optional<SlackDeliveryPart> nextPart(
            SlackDeliveryClaim claim,
            Duration leaseDuration);

    /** Acknowledges a text timestamp or file ID; the parent retains the first text item's timestamp. */
    boolean markPartSent(SlackDeliveryClaim claim, SlackDeliveryPart part, String acknowledgementId);

    boolean markPartFailed(
            SlackDeliveryClaim claim,
            SlackDeliveryPart part,
            SlackDeliveryFailureCategory category,
            Optional<String> slackMessageTs);

    boolean scheduleRetry(SlackDeliveryClaim claim, SlackDeliveryFailureCategory category, Duration delay);

    boolean allocated(SlackDeliveryClaim claim, SlackDeliveryPart part, SlackUploadTicket ticket);

    boolean uploaded(SlackDeliveryClaim claim, SlackDeliveryPart part);

    /** Commits one-time intent before remote I/O. An abandoned intent is never automatically resent. */
    boolean beginFinalization(SlackDeliveryClaim claim, SlackDeliveryPart part, Duration leaseDuration);

    /** Only for a proven safe remote rejection (currently Retry-After rate limiting), never a timeout. */
    boolean rejectFinalization(SlackDeliveryClaim claim, SlackDeliveryPart part);

    boolean unconfirmed(SlackDeliveryClaim claim, SlackDeliveryPart part);

    boolean releaseStage(SlackDeliveryClaim claim);

    Optional<SlackDeliveryView> read(UUID deliveryId);

    List<SlackDeliveryView> findBySource(String teamId, String channelId, String messageTs);
}
