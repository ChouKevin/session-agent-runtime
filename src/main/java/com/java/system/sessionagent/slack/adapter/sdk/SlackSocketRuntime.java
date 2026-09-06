package com.java.system.sessionagent.slack.adapter.sdk;


interface SlackSocketRuntime {

    void startAsync() throws Exception;

    void stop() throws Exception;
}
