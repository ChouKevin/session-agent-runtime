package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackProgressClaim;
import com.java.system.sessionagent.slack.domain.SlackProgressUpdate;
import java.time.Duration;
import java.util.Optional;

/** Durable fenced ownership of updates to confirmed question notices. */
public interface SlackProgressStore {
    void discover();
    Optional<SlackProgressClaim> claimNext(String workerId, Duration leaseDuration);
    Optional<SlackProgressUpdate> refresh(SlackProgressClaim claim);
    boolean saveQuestionPermalink(SlackProgressClaim claim, String permalink);
    boolean markSent(SlackProgressClaim claim, long revision);
    boolean recordFailure(SlackProgressClaim claim, long revision, SlackDeliveryFailureCategory category,
            Optional<Duration> retryAfter);
}
