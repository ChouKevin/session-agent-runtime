package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessageCode;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MicrometerConversationTelemetry implements ConversationTelemetry {

    private static final Pattern PORTABLE_TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Set<String> INTAKE_OUTCOMES = Set.of("ACCEPTED", "REJECTED");
    private static final Set<String> JOB_OUTCOMES = Set.of("EMPTY", "CLAIMED", "COMPLETED", "OWNERSHIP_LOST", "FAILED");
    private static final Set<String> MODEL_OUTCOMES = Set.of("SUCCESS", "FAILURE");
    private static final Set<String> MODEL_CATEGORIES = Set.of(
            "STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "OTHER", "UNAVAILABLE", "RESPONSE", "OUTPUT_INVALID");
    private static final Set<String> TOOL_OUTCOMES = Set.of("SUCCESS", "INVALID_INPUT", "FAILURE");
    private static final Set<String> RUNTIME_MESSAGE_CODES = Arrays.stream(RuntimeMessageCode.values())
            .map(RuntimeMessageCode::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> RETRY_CATEGORIES = Set.of("MODEL", "TOOL", "STORAGE", "DEPENDENCY");

    private final MeterRegistry meterRegistry;

    public MicrometerConversationTelemetry(MeterRegistry meterRegistry) {
        Assert.notNull(meterRegistry, "Meter registry must not be null");
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void intake(String outcome) {
        increment("session_agent.intake", "outcome", bounded(outcome, INTAKE_OUTCOMES));
    }

    @Override
    public void job(String outcome) {
        increment("session_agent.job", "outcome", bounded(outcome, JOB_OUTCOMES));
    }

    @Override
    public void model(String outcome, Optional<String> category, ModelUsage usage, Duration duration) {
        Assert.notNull(category, "Model category must not be null");
        Assert.notNull(usage, "Model usage must not be null");
        Assert.notNull(duration, "Model duration must not be null");
        String boundedOutcome = bounded(outcome, MODEL_OUTCOMES);
        String boundedCategory = bounded(category.orElse("UNAVAILABLE"), MODEL_CATEGORIES);
        increment("session_agent.model", "outcome", boundedOutcome,
                "category", boundedCategory,
                "usage_available", Boolean.toString(usage.available()));
        recordDuration("session_agent.model.duration", duration, "outcome", boundedOutcome,
                "category", boundedCategory, "usage_available", Boolean.toString(usage.available()));
        if (usage.available()) {
            meterRegistry.counter("session_agent.model.tokens", "kind", "prompt").increment(usage.promptTokens());
            meterRegistry.counter("session_agent.model.tokens", "kind", "completion").increment(usage.completionTokens());
            meterRegistry.counter("session_agent.model.tokens", "kind", "total").increment(usage.totalTokens());
        }
    }

    @Override
    public void tool(String toolName, String outcome, Duration duration) {
        Assert.notNull(duration, "Tool duration must not be null");
        String boundedToolName = PORTABLE_TOOL_NAME.matcher(toolName).matches() ? "PORTABLE" : "OTHER";
        String boundedOutcome = bounded(outcome, TOOL_OUTCOMES);
        increment("session_agent.tool", "tool", boundedToolName, "outcome", boundedOutcome);
        recordDuration("session_agent.tool.duration", duration, "tool", boundedToolName, "outcome", boundedOutcome);
    }

    @Override
    public void feedback(String code) {
        increment("session_agent.feedback", "code", bounded(code, RUNTIME_MESSAGE_CODES));
    }

    @Override
    public void retry(String dependency, Duration delay) {
        Assert.notNull(delay, "Retry delay must not be null");
        increment("session_agent.retry", "category", bounded(dependency, RETRY_CATEGORIES));
    }

    private void increment(String metric, String... tags) {
        meterRegistry.counter(metric, tags).increment();
    }

    private void recordDuration(String metric, Duration duration, String... tags) {
        meterRegistry.timer(metric, tags).record(duration);
    }

    private static String bounded(String candidate, Set<String> allowed) {
        return allowed.contains(candidate) ? candidate : "OTHER";
    }
}
