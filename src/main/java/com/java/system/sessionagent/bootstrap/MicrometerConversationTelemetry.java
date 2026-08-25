package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public final class MicrometerConversationTelemetry implements ConversationTelemetry {

    private static final Set<String> TOOL_NAMES = Set.of(
            "list_repositories", "codebase_list_entry_points", "codebase_lookup_api_route", "codebase_suggest_api_route",
            "codebase_outgoing_call_graph", "codebase_incoming_call_graph", "codebase_search_code_facts",
            "codebase_get_code_fact", "codebase_discover_event_listeners", "codebase_discover_method_implementations",
            "codebase_discover_type_members", "codebase_find_internal_references", "codebase_get_evidence_source",
            "codebase_get_method_source", "codebase_get_source_segment", "codebase_resolve_source_symbol");
    private static final Set<String> INTAKE_OUTCOMES = Set.of("ACCEPTED", "REJECTED");
    private static final Set<String> JOB_OUTCOMES = Set.of("EMPTY", "CLAIMED", "COMPLETED", "OWNERSHIP_LOST", "FAILED");
    private static final Set<String> MODEL_OUTCOMES = Set.of("SUCCESS", "FAILURE");
    private static final Set<String> FINISH_REASONS = Set.of("STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "OTHER", "UNAVAILABLE");
    private static final Set<String> TOOL_OUTCOMES = Set.of(
            "SUCCESS", "INVALID_INPUT", "INPUT_TOO_LARGE", "REPOSITORY_NOT_FOUND", "REVISION_OUTDATED",
            "INDEX_NOT_READY", "INDEX_CONTRACT_MISMATCH", "CODE_FACT_NOT_FOUND", "CODE_FACT_KIND_UNSUPPORTED",
            "INVALID_QUERY", "SEMANTIC_INDEX_UNAVAILABLE", "FORBIDDEN", "INVALID_RESPONSE", "STALE");
    private static final Set<String> FEEDBACK_CODES = Set.of(
            "INVALID_TOOL_INPUT", "TOOL_INPUT_TOO_LARGE", "UNKNOWN_REPOSITORY",
            "REVISION_OUTDATED", "INDEX_NOT_READY", "INDEX_CONTRACT_MISMATCH", "CODE_FACT_NOT_FOUND",
            "CODE_FACT_KIND_UNSUPPORTED", "INVALID_QUERY", "INVALID_CITATION", "CALL_LIMIT_REACHED", "MODEL_OUTPUT_INVALID",
            "CONTEXT_TOO_LARGE", "DATABASE_CONTRACT_ERROR", "DEPENDENCY_UNAVAILABLE", "DEPENDENCY_FORBIDDEN",
            "DEPENDENCY_INVALID_RESPONSE");
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
    public void model(String outcome, Optional<String> finishReason, ModelUsage usage) {
        Assert.notNull(finishReason, "Model finish reason must not be null");
        Assert.notNull(usage, "Model usage must not be null");
        increment("session_agent.model", "outcome", bounded(outcome, MODEL_OUTCOMES),
                "finish_reason", bounded(finishReason.orElse("UNAVAILABLE"), FINISH_REASONS),
                "usage_available", Boolean.toString(usage.available()));
        if (usage.available()) {
            meterRegistry.counter("session_agent.model.tokens", "kind", "prompt").increment(usage.promptTokens());
            meterRegistry.counter("session_agent.model.tokens", "kind", "completion").increment(usage.completionTokens());
            meterRegistry.counter("session_agent.model.tokens", "kind", "total").increment(usage.totalTokens());
        }
    }

    @Override
    public void tool(String toolName, String outcome, Optional<String> repositoryId, Optional<String> revision) {
        Assert.notNull(repositoryId, "Repository ID must not be null");
        Assert.notNull(revision, "Revision must not be null");
        increment("session_agent.tool", "tool", bounded(toolName, TOOL_NAMES), "outcome", bounded(outcome, TOOL_OUTCOMES));
    }

    @Override
    public void feedback(String code) {
        increment("session_agent.feedback", "code", bounded(code, FEEDBACK_CODES));
    }

    @Override
    public void retry(String dependency, Duration delay) {
        Assert.notNull(delay, "Retry delay must not be null");
        increment("session_agent.retry", "category", bounded(dependency, RETRY_CATEGORIES));
    }

    private void increment(String metric, String... tags) {
        meterRegistry.counter(metric, tags).increment();
    }

    private static String bounded(String candidate, Set<String> allowed) {
        return allowed.contains(candidate) ? candidate : "OTHER";
    }
}
