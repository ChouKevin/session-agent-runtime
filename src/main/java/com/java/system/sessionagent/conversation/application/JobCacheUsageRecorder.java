package com.java.system.sessionagent.conversation.application;

import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import java.util.Objects;

/** Optional accounting must never turn a completed provider response into a retry. */
public final class JobCacheUsageRecorder {
    private final ConversationStore store;

    public JobCacheUsageRecorder(ConversationStore store) {
        this.store = Objects.requireNonNull(store, "Conversation store must not be null");
    }

    public void record(MessageWorkClaim claim, ModelCallObservation observation) {
        Objects.requireNonNull(claim, "Message work claim must not be null");
        Objects.requireNonNull(observation, "Model call observation must not be null");
        try {
            store.recordModelUsage(claim, observation);
        } catch (RuntimeException ignored) {
            // Keep the reserved attempt unknown if this optional accounting transaction fails.
            // Required lease, history, progress and completion operations retain their own failure contracts.
        }
    }
}
