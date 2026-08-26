package com.java.system.sessionagent.model;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

final class SpringAiCallCapture implements CallAdvisor {

    private final AtomicBoolean used = new AtomicBoolean();
    private ChatClientRequest request;
    private ChatResponse response;
    private RuntimeException providerFailure;
    private Instant startedAt;
    private Instant endedAt;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain callAdvisorChain) {
        Assert.notNull(request, "Chat client request must not be null");
        Assert.notNull(callAdvisorChain, "Call advisor chain must not be null");
        if (!used.compareAndSet(false, true)) {
            throw new IllegalStateException("Spring AI call capture can only be used once");
        }
        this.request = request;
        this.startedAt = Instant.now();
        try {
            ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(request);
            this.response = chatClientResponse.chatResponse();
            return chatClientResponse;
        } catch (RuntimeException exception) {
            this.providerFailure = exception;
            throw exception;
        } finally {
            this.endedAt = Instant.now();
        }
    }

    @Override
    public String getName() {
        return "Spring AI call capture";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    Optional<ChatClientRequest> request() {
        return Optional.ofNullable(request);
    }

    Optional<ChatResponse> response() {
        return Optional.ofNullable(response);
    }

    Optional<RuntimeException> providerFailure() {
        return Optional.ofNullable(providerFailure);
    }

    Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    Optional<Instant> endedAt() {
        return Optional.ofNullable(endedAt);
    }
}
