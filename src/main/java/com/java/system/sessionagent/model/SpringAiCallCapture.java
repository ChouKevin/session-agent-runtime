package com.java.system.sessionagent.model;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

import java.time.Instant;
import java.time.Clock;
import java.util.Optional;

final class SpringAiCallCapture implements CallAdvisor {

    private final Clock clock;
    private Optional<ChatClientRequest> request = Optional.empty();
    private Optional<ChatResponse> chatResponse = Optional.empty();
    private Optional<RuntimeException> providerFailure = Optional.empty();
    private Optional<Instant> startedAt = Optional.empty();
    private Optional<Instant> completedAt = Optional.empty();

    SpringAiCallCapture(Clock clock) {
        Assert.notNull(clock, "Clock must not be null");
        this.clock = clock;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain callAdvisorChain) {
        Assert.notNull(request, "Chat client request must not be null");
        Assert.notNull(callAdvisorChain, "Call advisor chain must not be null");
        startedAt = Optional.of(clock.instant());
        this.request = Optional.of(request);
        try {
            ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(request);
            chatResponse = Optional.ofNullable(chatClientResponse.chatResponse());
            return chatClientResponse;
        } catch (RuntimeException exception) {
            providerFailure = Optional.of(exception);
            throw exception;
        } finally {
            completedAt = Optional.of(clock.instant());
        }
    }

    @Override
    public String getName() {
        return "Session Agent model-call capture";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    Optional<ChatClientRequest> request() {
        return request;
    }

    Optional<ChatResponse> chatResponse() {
        return chatResponse;
    }

    Optional<RuntimeException> providerFailure() {
        return providerFailure;
    }

    Optional<Instant> startedAt() {
        return startedAt;
    }

    Optional<Instant> completedAt() {
        return completedAt;
    }
}
