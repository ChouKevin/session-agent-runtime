package com.java.system.sessionagent.slack.application;

import com.java.system.sessionagent.slack.configuration.SlackDeliveryProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackProgressClaim;
import com.java.system.sessionagent.slack.domain.SlackProgressUpdate;
import com.java.system.sessionagent.slack.port.out.SlackProgressStore;
import com.java.system.sessionagent.slack.port.out.SlackWebApi;
import java.util.Objects;
import java.util.Optional;
import org.springframework.util.Assert;

/** One durable, workspace-gated update attempt per poll; never executes question work. */
public final class SlackProgressWorker {
    private final SlackProgressStore store;
    private final SlackWebApi api;
    private final SlackDeliveryProperties delivery;
    private final String workerId;

    public SlackProgressWorker(SlackProgressStore store, SlackWebApi api, SlackDeliveryProperties delivery, String workerId) {
        this.store = Objects.requireNonNull(store, "Progress store must not be null");
        this.api = Objects.requireNonNull(api, "Slack Web API must not be null");
        this.delivery = Objects.requireNonNull(delivery, "Delivery properties must not be null");
        Assert.hasText(workerId, "Progress worker ID must not be blank");
        this.workerId = workerId;
    }

    public boolean poll() {
        store.discover();
        Optional<SlackProgressClaim> claimed = store.claimNext(workerId, delivery.leaseDuration());
        if (claimed.isEmpty()) {
            return false;
        }
        SlackProgressClaim claim = claimed.orElseThrow();
        Optional<SlackProgressUpdate> refreshed = store.refresh(claim);
        if (refreshed.isEmpty()) {
            return true;
        }
        SlackProgressUpdate update = refreshed.orElseThrow();
        try {
            if (update.questionPermalink().isEmpty()) {
                String permalink = api.getPermalink(update.request().channelId(), update.sourceMessageTs());
                store.saveQuestionPermalink(claim, permalink);
                // One bounded outbound call per lease. Next claim observes current job state before updating.
                return true;
            }
            api.update(update.request());
        } catch (SlackPostFailure failure) {
            store.recordFailure(claim, update.revision(), failure.category(), failure.retryAfter());
            return true;
        } catch (RuntimeException exception) {
            // An ambiguous outcome retries the latest database observation at the same known timestamp.
            store.recordFailure(claim, update.revision(), SlackDeliveryFailureCategory.TRANSIENT, Optional.empty());
            return true;
        }
        // A rejected acknowledgment is not success: leave ownership to expire and recover through the gate.
        // The store, not the terminal flag, owns both the settling pass and retirement.
        store.markSent(claim, update.revision());
        return true;
    }
}
