package com.java.system.sessionagent.semantic.domain;

import org.springframework.util.Assert;

public record RepositorySummary(RepositoryId repositoryId, String displayName) {

    public RepositorySummary {
        Assert.notNull(repositoryId, "Repository ID must not be null");
        Assert.hasText(displayName, "Repository display name must not be blank");
    }
}
