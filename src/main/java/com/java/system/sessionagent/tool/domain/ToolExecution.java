package com.java.system.sessionagent.tool.domain;

import org.springframework.util.Assert;

import java.util.Optional;

public record ToolExecution(
        ToolName name,
        String version,
        ToolKind kind,
        String canonicalArguments,
        Optional<String> repositoryId,
        Optional<String> revision,
        String dataJson) {

    public ToolExecution {
        Assert.notNull(name, "Tool name must not be null");
        Assert.hasText(version, "Tool version must not be blank");
        Assert.notNull(kind, "Tool kind must not be null");
        Assert.hasText(canonicalArguments, "Canonical arguments must not be blank");
        Assert.notNull(repositoryId, "Repository ID must not be null");
        Assert.notNull(revision, "Revision must not be null");
        repositoryId.ifPresent(value -> Assert.hasText(value, "Repository ID must not be blank"));
        revision.ifPresent(value -> Assert.hasText(value, "Revision must not be blank"));
        if (kind == ToolKind.CATALOG) {
            Assert.isTrue(repositoryId.isEmpty() && revision.isEmpty(),
                    "Catalog tool result must not have repository or revision");
        }
        if (kind == ToolKind.SOURCE) {
            Assert.isTrue(repositoryId.isPresent() && revision.isPresent(),
                    "Source tool result requires repository and revision");
        }
        Assert.hasText(dataJson, "Tool data JSON must not be blank");
    }
}
