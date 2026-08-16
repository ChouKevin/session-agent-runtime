package com.java.system.sessionagent.model;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PromptResource {

    private static final String RESOURCE_PATH = "prompts/conversation/system.md";

    private final String content;

    public PromptResource() {
        this(new ClassPathResource(RESOURCE_PATH));
    }

    public PromptResource(Resource resource) {
        Assert.notNull(resource, "Conversation system prompt resource must not be null");
        try (InputStream inputStream = resource.getInputStream()) {
            this.content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Conversation system prompt could not be loaded");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("Conversation system prompt must not be blank");
        }
    }

    public String content() {
        return content;
    }
}
