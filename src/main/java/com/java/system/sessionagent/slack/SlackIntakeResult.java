package com.java.system.sessionagent.slack;

import org.springframework.util.Assert;

import java.util.Optional;
import java.util.UUID;

public record SlackIntakeResult(
        SlackEventOutcome outcome,
        SlackIntakeDisposition disposition,
        Optional<UUID> sessionId,
        Optional<UUID> messageJobId,
        Optional<SlackSessionResolution> sessionResolution) {

    public SlackIntakeResult {
        Assert.notNull(outcome, "Slack event outcome must not be null");
        Assert.notNull(disposition, "Slack intake disposition must not be null");
        Assert.notNull(sessionId, "Slack session ID must not be null");
        Assert.notNull(messageJobId, "Slack message job ID must not be null");
        Assert.notNull(sessionResolution, "Slack session resolution must not be null");
        if (outcome == SlackEventOutcome.ACCEPTED) {
            Assert.isTrue(sessionId.isPresent() && messageJobId.isPresent() && sessionResolution.isPresent(),
                    "Accepted Slack intake must include correlation identifiers");
            Assert.isTrue(disposition == SlackIntakeDisposition.NEW_ACCEPTED
                            || disposition == SlackIntakeDisposition.DUPLICATE_ACCEPTED,
                    "Accepted Slack intake must use an accepted disposition");
        } else {
            Assert.isTrue(sessionId.isEmpty() && messageJobId.isEmpty() && sessionResolution.isEmpty(),
                    "Ignored Slack intake must not include correlation identifiers");
            Assert.isTrue(disposition == SlackIntakeDisposition.NEW_IGNORED
                            || disposition == SlackIntakeDisposition.DUPLICATE_IGNORED,
                    "Ignored Slack intake must use an ignored disposition");
        }
    }

    public static SlackIntakeResult newSessionAccepted(UUID sessionId, UUID messageJobId) {
        return accepted(SlackIntakeDisposition.NEW_ACCEPTED, SlackSessionResolution.CREATED, sessionId, messageJobId);
    }

    public static SlackIntakeResult resolvedSessionAccepted(UUID sessionId, UUID messageJobId) {
        return accepted(SlackIntakeDisposition.NEW_ACCEPTED, SlackSessionResolution.RESOLVED, sessionId, messageJobId);
    }

    public static SlackIntakeResult duplicateAccepted(UUID sessionId, UUID messageJobId) {
        return accepted(SlackIntakeDisposition.DUPLICATE_ACCEPTED, SlackSessionResolution.RESOLVED, sessionId, messageJobId);
    }

    public static SlackIntakeResult newIgnored() {
        return ignored(SlackIntakeDisposition.NEW_IGNORED);
    }

    public static SlackIntakeResult duplicateIgnored() {
        return ignored(SlackIntakeDisposition.DUPLICATE_IGNORED);
    }

    private static SlackIntakeResult accepted(
            SlackIntakeDisposition disposition,
            SlackSessionResolution sessionResolution,
            UUID sessionId,
            UUID messageJobId) {
        Assert.notNull(sessionId, "Slack session ID must not be null");
        Assert.notNull(messageJobId, "Slack message job ID must not be null");
        return new SlackIntakeResult(SlackEventOutcome.ACCEPTED, disposition, Optional.of(sessionId), Optional.of(messageJobId),
                Optional.of(sessionResolution));
    }

    private static SlackIntakeResult ignored(SlackIntakeDisposition disposition) {
        return new SlackIntakeResult(SlackEventOutcome.IGNORED, disposition, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
