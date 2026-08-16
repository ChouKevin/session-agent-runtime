package com.java.system.sessionagent.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MessageRequests {

    private MessageRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SendMessageRequest(
            @NotBlank @Size(max = 256) String sessionKey,
            @NotBlank @Size(max = 256) String participantId,
            @NotBlank @Size(max = 256) String sourceMessageId,
            @NotBlank String message) {
    }
}
