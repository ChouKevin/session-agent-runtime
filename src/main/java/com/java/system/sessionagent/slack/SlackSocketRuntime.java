package com.java.system.sessionagent.slack;

interface SlackSocketRuntime {

    void startAsync() throws Exception;

    void stop() throws Exception;
}
