package com.java.system.sessionagent.tool.application;

import org.springframework.util.Assert;

/** Formats typed tool failures as model-safe, provider-neutral observations. */
public final class ToolFailureOutput {

    private ToolFailureOutput() {
    }

    public static String format(ToolExecutionFailure failure) {
        Assert.notNull(failure, "Tool execution failure must not be null");
        return "Tool execution failed.\nCode: " + failure.code() + "\nMessage: " + failure.getMessage();
    }
}
