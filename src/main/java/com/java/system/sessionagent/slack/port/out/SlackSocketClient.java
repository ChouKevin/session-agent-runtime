package com.java.system.sessionagent.slack.port.out;

public interface SlackSocketClient {

    void start(SlackSocketConnectionListener listener) throws Exception;

    void stop() throws Exception;
}
