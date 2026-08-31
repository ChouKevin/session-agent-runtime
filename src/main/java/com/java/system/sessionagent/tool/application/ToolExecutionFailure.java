package com.java.system.sessionagent.tool.application;

import org.springframework.util.Assert;

public final class ToolExecutionFailure extends RuntimeException {

    private final String code;

    public ToolExecutionFailure(String code, String safeMessage) {
        super(safeMessage);
        Assert.hasText(code, "Tool failure code must not be blank");
        Assert.isTrue(code.length() <= 64, "Tool failure code must not exceed 64 characters");
        Assert.hasText(safeMessage, "Tool failure message must not be blank");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
