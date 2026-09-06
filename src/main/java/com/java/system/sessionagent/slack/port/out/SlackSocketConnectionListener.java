package com.java.system.sessionagent.slack.port.out;

public interface SlackSocketConnectionListener {

    void connected();

    void disconnected();
}
