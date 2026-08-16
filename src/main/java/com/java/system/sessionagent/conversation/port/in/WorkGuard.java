package com.java.system.sessionagent.conversation.port.in;

@FunctionalInterface
public interface WorkGuard {

    boolean stillOwned();
}
