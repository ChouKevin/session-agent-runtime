package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;

public record AssistantReply(String message, List<ResultId> citations) {
    public AssistantReply {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("assistant reply must contain text");
        }
        citations = List.copyOf(citations);
        if (citations.isEmpty() || new HashSet<>(citations).size() != citations.size()) {
            throw new IllegalArgumentException("assistant reply citations must be nonempty and unique");
        }
    }
}
