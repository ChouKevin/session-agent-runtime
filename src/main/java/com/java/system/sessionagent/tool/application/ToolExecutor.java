package com.java.system.sessionagent.tool.application;

@FunctionalInterface
public interface ToolExecutor<T> {

    String execute(T input);
}
