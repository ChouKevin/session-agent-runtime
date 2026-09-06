package com.java.system.sessionagent.slack.port.out;

import com.java.system.sessionagent.slack.domain.SlackPostRequest;

public interface SlackWebApi {

    String post(SlackPostRequest request);
}
