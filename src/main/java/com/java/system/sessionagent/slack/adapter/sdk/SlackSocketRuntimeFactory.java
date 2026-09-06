package com.java.system.sessionagent.slack.adapter.sdk;

import com.java.system.sessionagent.slack.port.out.SlackSocketConnectionListener;

@FunctionalInterface
interface SlackSocketRuntimeFactory {

    SlackSocketRuntime create(SlackSocketConnectionListener listener) throws Exception;
}
