package com.java.system.sessionagent.slack;

public interface SlackSocketClient {

    void start(SlackSocketConnectionListener listener) throws Exception;

    void stop() throws Exception;
}
