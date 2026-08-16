package com.java.system.sessionagent.semantic.domain;

import org.springframework.util.Assert;

public record RepositoryId(String value) {

    public RepositoryId {
        Assert.hasText(value, "Repository ID must not be blank");
    }
}
