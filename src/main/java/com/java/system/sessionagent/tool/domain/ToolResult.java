package com.java.system.sessionagent.tool.domain;

import org.springframework.util.Assert;

import java.util.Optional;

public record ToolResult(Optional<String> repositoryId, Optional<String> revision, String dataJson) {

    public ToolResult {
        Assert.notNull(repositoryId, "Repository ID must not be null");
        Assert.notNull(revision, "Revision must not be null");
        repositoryId.ifPresent(value -> Assert.hasText(value, "Repository ID must not be blank"));
        revision.ifPresent(value -> Assert.hasText(value, "Revision must not be blank"));
        Assert.isTrue(repositoryId.isPresent() == revision.isPresent(),
                "Repository ID and revision must be present together");
        Assert.hasText(dataJson, "Tool data JSON must not be blank");
    }
}
