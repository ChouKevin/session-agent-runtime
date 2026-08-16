package com.java.system.sessionagent.semantic.domain;

import org.springframework.util.Assert;

import java.util.Optional;

public record RepositoryStatus(RepositorySummary repository, Optional<RepositoryRevision> currentRevision) {

    public RepositoryStatus {
        Assert.notNull(repository, "Repository summary must not be null");
        Assert.notNull(currentRevision, "Current revision must not be null");
    }
}
