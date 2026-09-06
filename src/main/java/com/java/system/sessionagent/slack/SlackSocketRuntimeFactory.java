package com.java.system.sessionagent.slack;

@FunctionalInterface
interface SlackSocketRuntimeFactory {

    SlackSocketRuntime create(SlackSocketConnectionListener listener) throws Exception;
}
