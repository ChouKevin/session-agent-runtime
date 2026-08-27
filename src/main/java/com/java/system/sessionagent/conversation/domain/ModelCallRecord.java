package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record ModelCallRecord(
        UUID id,
        SessionId sessionId,
        MessageJobId messageJobId,
        int runtimeCallOrdinal,
        int providerAttempt,
        ModelCallPhase phase,
        ModelCallOutcome outcome,
        String modelName,
        String rawPrompt,
        Optional<String> rawCompletion,
        Optional<String> rawToolCalls,
        Optional<String> finishReason,
        Optional<String> decodeError,
        Optional<String> providerError,
        ModelUsage usage,
        Instant startedAt,
        Instant completedAt) {

    public ModelCallRecord {
        Assert.notNull(id, "Diagnostic ID must not be null");
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(messageJobId, "Message job ID must not be null");
        Assert.isTrue(runtimeCallOrdinal >= 1 && runtimeCallOrdinal <= 12,
                "Runtime call ordinal must be between 1 and 12");
        Assert.isTrue(providerAttempt >= 1, "Provider attempt must be positive");
        Assert.notNull(phase, "Model call phase must not be null");
        Assert.notNull(outcome, "Model call outcome must not be null");
        Assert.hasText(modelName, "Model name must not be blank");
        Assert.hasText(rawPrompt, "Raw prompt must not be blank");
        Assert.notNull(rawCompletion, "Raw completion must not be null");
        Assert.notNull(rawToolCalls, "Raw tool calls must not be null");
        Assert.notNull(finishReason, "Finish reason must not be null");
        Assert.notNull(decodeError, "Decode error must not be null");
        Assert.notNull(providerError, "Provider error must not be null");
        Assert.notNull(usage, "Model usage must not be null");
        Assert.notNull(startedAt, "Start time must not be null");
        Assert.notNull(completedAt, "Completion time must not be null");
        Assert.isTrue(!completedAt.isBefore(startedAt), "Completion time must not precede start time");

        Set<ModelCallOutcome> allowedOutcomes = switch (phase) {
            case PLAN -> Set.of(
                    ModelCallOutcome.TOOL_CALL,
                    ModelCallOutcome.ANSWER_READY,
                    ModelCallOutcome.INVALID_RESPONSE,
                    ModelCallOutcome.PROVIDER_FAILURE);
            case FINAL_REPLY -> Set.of(
                    ModelCallOutcome.FINAL_REPLY,
                    ModelCallOutcome.INVALID_RESPONSE,
                    ModelCallOutcome.PROVIDER_FAILURE);
        };
        Assert.isTrue(allowedOutcomes.contains(outcome), "Outcome is not valid for the model-call phase");

        boolean providerFailed = outcome.equals(ModelCallOutcome.PROVIDER_FAILURE);
        boolean decodingFailed = outcome.equals(ModelCallOutcome.INVALID_RESPONSE);
        boolean calledTool = outcome.equals(ModelCallOutcome.TOOL_CALL);
        Assert.isTrue(providerFailed == providerError.isPresent(),
                "Provider error must be present only for provider failure");
        Assert.isTrue(decodingFailed == decodeError.isPresent(),
                "Decode error must be present only for invalid response");
        Assert.isTrue(!calledTool || rawToolCalls.isPresent(),
                "A tool-call outcome must retain raw tool calls");
        Assert.isTrue(!calledTool || rawCompletion.isEmpty(),
                "A tool-call outcome must not contain answer text");
        Assert.isTrue(
                !Set.of(
                        ModelCallOutcome.ANSWER_READY,
                        ModelCallOutcome.FINAL_REPLY,
                        ModelCallOutcome.PROVIDER_FAILURE).contains(outcome)
                        || rawToolCalls.isEmpty(),
                "A non-tool success or provider failure must not contain raw tool calls");
        if (Set.of(ModelCallOutcome.ANSWER_READY, ModelCallOutcome.FINAL_REPLY).contains(outcome)) {
            Assert.isTrue(rawCompletion.filter(StringUtils::hasText).isPresent(),
                    "A completion outcome must retain nonblank raw completion text");
        }
    }
}
