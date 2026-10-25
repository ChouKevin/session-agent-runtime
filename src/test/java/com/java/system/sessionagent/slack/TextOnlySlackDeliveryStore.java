package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.slack.domain.SlackDeliveryClaim;
import com.java.system.sessionagent.slack.domain.SlackDeliveryPart;
import com.java.system.sessionagent.slack.domain.SlackUploadTicket;
import com.java.system.sessionagent.slack.port.out.SlackDeliveryStore;
import java.time.Duration;

/** Narrow fixtures reject accidental file transport rather than pretending to upload. */
public interface TextOnlySlackDeliveryStore extends SlackDeliveryStore {
    @Override default boolean allocated(SlackDeliveryClaim claim, SlackDeliveryPart part, SlackUploadTicket ticket) {
        throw new AssertionError("Text-only store received allocation");
    }
    @Override default boolean uploaded(SlackDeliveryClaim claim, SlackDeliveryPart part) {
        throw new AssertionError("Text-only store received upload acknowledgement");
    }
    @Override default boolean beginFinalization(SlackDeliveryClaim claim, SlackDeliveryPart part, Duration leaseDuration) {
        throw new AssertionError("Text-only store received finalization intent");
    }
    @Override default boolean rejectFinalization(SlackDeliveryClaim claim, SlackDeliveryPart part) {
        throw new AssertionError("Text-only store received rejected finalization");
    }
    @Override default boolean unconfirmed(SlackDeliveryClaim claim, SlackDeliveryPart part) {
        throw new AssertionError("Text-only store received uncertain finalization");
    }
    @Override default boolean releaseStage(SlackDeliveryClaim claim) {
        throw new AssertionError("Text-only store received stage release");
    }
}
