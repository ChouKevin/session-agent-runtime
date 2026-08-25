package com.java.system.sessionagent.semantic.domain;

import org.springframework.util.Assert;

public record RepositorySummary(RepositoryId repositoryId, RepositoryRevision revision) {

    public RepositorySummary {
        Assert.notNull(repositoryId, "Repository ID must not be null");
        Assert.notNull(revision, "Repository revision must not be null");
    }
}
