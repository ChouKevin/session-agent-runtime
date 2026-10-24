package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;

public interface SlackWebApi {

    String post(SlackPostRequest request);

    void update(SlackUpdateRequest request);

    String getPermalink(String channelId, String messageTs);
}
