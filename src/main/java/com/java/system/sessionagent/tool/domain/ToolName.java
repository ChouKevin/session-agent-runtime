package com.java.system.sessionagent.tool.domain;

import org.springframework.util.Assert;

import java.util.regex.Pattern;

public record ToolName(String value) {

    private static final Pattern PORTABLE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    public ToolName {
        Assert.hasText(value, "Tool name must not be blank");
        Assert.isTrue(PORTABLE_NAME.matcher(value).matches(), "Tool name must be portable");
    }
}
