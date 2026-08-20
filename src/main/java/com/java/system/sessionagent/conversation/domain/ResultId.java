package com.java.system.sessionagent.conversation.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import org.springframework.util.Assert;

public record ResultId(@JsonProperty(required = true) @NotBlank String value) {

    public ResultId {
        Assert.hasText(value, "Result ID must not be blank");
    }
}
