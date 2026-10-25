package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;

/** Post-only fixtures fail loudly if answer delivery accidentally starts updating notices. */
@FunctionalInterface
public interface PostingSlackWebApi extends TextSlackWebApi {
    @Override
    default void update(SlackUpdateRequest request) {
        throw new AssertionError("Post-only fixture received a progress update");
    }

    @Override
    default String getPermalink(String channelId, String messageTs) {
        throw new AssertionError("Post-only fixture received a permalink lookup");
    }

}
