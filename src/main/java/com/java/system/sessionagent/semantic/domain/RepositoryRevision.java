package com.java.system.sessionagent.semantic.domain;

import org.springframework.util.Assert;

public record RepositoryRevision(String value) {

    public RepositoryRevision {
        Assert.hasText(value, "Repository revision must not be blank");
    }
}
