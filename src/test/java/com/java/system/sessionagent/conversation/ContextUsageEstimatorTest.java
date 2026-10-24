package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.AssistantMessage;
import com.java.system.sessionagent.conversation.domain.ContextEstimate;
import com.java.system.sessionagent.conversation.domain.ContextUsageCheckpoint;
import com.java.system.sessionagent.conversation.domain.ContextUsageEstimator;
import com.java.system.sessionagent.conversation.domain.ContextUsageProjection;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.SkillMessage;
import com.java.system.sessionagent.skill.domain.SkillResource;
import com.java.system.sessionagent.conversation.domain.ContextHistoryView;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.domain.SessionMessage;
import com.java.system.sessionagent.conversation.domain.SkillCommand;
import com.java.system.sessionagent.conversation.domain.SkillCommandState;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextUsageEstimatorTest {

    @Test
    void includes_actual_skill_content_in_context_estimates() {
        ContextUsageProjection base = projection(List.of());
        SkillMessage skill = new SkillMessage(new SessionId("session"), new SessionSequence(4),
                Optional.of(new MessageJobId("job")), Instant.EPOCH, MessageRole.SKILL,
                new SkillResource("skill://review/SKILL.md", "a".repeat(64), "content ".repeat(100)));
        ContextUsageProjection withSkill = new ContextUsageProjection(base.model(), base.systemPrompt(),
                base.toolDefinitions(), List.of(skill), 0, Optional.empty(), "eligibility");
        ContextUsageProjection empty = new ContextUsageProjection(base.model(), base.systemPrompt(),
                base.toolDefinitions(), List.of(), 0, Optional.empty(), "eligibility");
        ContextUsageEstimator estimator = new ContextUsageEstimator();
        assertThat(estimator.estimate(withSkill, Optional.empty()).tokens())
                .isGreaterThan(estimator.estimate(empty, Optional.empty()).tokens());
    }
    @Test
    void uses_a_same_shape_checkpoint_with_only_the_trailing_history_and_rejects_stale_shapes() {
        ContextUsageEstimator estimator = new ContextUsageEstimator();
        ContextUsageProjection projection = projection(List.of(
                definition("second", Map.of("properties", Map.of("z", Map.of("type", "string"), "a", Map.of("type", "integer")))),
                definition("first", Map.of("required", List.of("name"), "type", "object"))));
        ContextUsageProjection reorderedTools = projection(List.of(
                definition("first", Map.of("type", "object", "required", List.of("name"))),
                definition("second", Map.of("properties", Map.of("a", Map.of("type", "integer"), "z", Map.of("type", "string"))))));
        String fingerprint = estimator.requestShapeFingerprint(projection);
        ContextUsageCheckpoint checkpoint = new ContextUsageCheckpoint(projection.model(), 1, new SessionSequence(2), 70, 30, 100,
                fingerprint, 0, Instant.EPOCH);

        ContextEstimate continued = estimator.estimate(projection, Optional.of(checkpoint));
        ContextEstimate stale = estimator.estimate(projection(List.of(definition("changed", Map.of()))), Optional.of(checkpoint));

        assertThat(estimator.requestShapeFingerprint(reorderedTools)).isEqualTo(fingerprint);
        assertThat(continued.basis()).isEqualTo(ContextEstimate.Basis.PROVIDER_PLUS_TRAILING_ESTIMATE);
        assertThat(continued.tokens()).isGreaterThan(100);
        assertThat(stale.basis()).isEqualTo(ContextEstimate.Basis.FULL_ESTIMATE);
        assertThat(stale.tokens()).isGreaterThan(0);
    }

    @Test
    void falls_back_to_full_estimate_when_matching_checkpoint_boundary_is_absent_from_current_history() {
        ContextUsageEstimator estimator = new ContextUsageEstimator();
        ContextUsageProjection checkpointProjection = projection(List.of(
                definition("second", Map.of("properties", Map.of("z", Map.of("type", "string"), "a", Map.of("type", "integer")))),
                definition("first", Map.of("required", List.of("name"), "type", "object"))));
        String fingerprint = estimator.requestShapeFingerprint(checkpointProjection);
        ContextUsageCheckpoint checkpoint = new ContextUsageCheckpoint(checkpointProjection.model(), 1,
                new SessionSequence(2), 70, 30, 100, fingerprint,
                checkpointProjection.compactGeneration(), Instant.EPOCH);
        ContextUsageProjection currentProjection = new ContextUsageProjection(
                checkpointProjection.model(),
                checkpointProjection.systemPrompt(),
                checkpointProjection.toolDefinitions(),
                List.of(checkpointProjection.history().get(0), checkpointProjection.history().get(2)),
                checkpointProjection.compactGeneration(), Optional.empty(), checkpointProjection.eligibilityFingerprint());

        ContextEstimate withCheckpoint = estimator.estimate(currentProjection, Optional.of(checkpoint));
        ContextEstimate withoutCheckpoint = estimator.estimate(currentProjection, Optional.empty());

        assertThat(withCheckpoint.basis()).isEqualTo(ContextEstimate.Basis.FULL_ESTIMATE);
        assertThat(withCheckpoint.tokens()).isEqualTo(withoutCheckpoint.tokens());
    }

    @Test
    void fingerprints_opaque_tool_schemas_that_contain_json_null_values() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("default", null);
        schema.put("type", "string");
        ContextUsageEstimator estimator = new ContextUsageEstimator();

        String fingerprint = estimator.requestShapeFingerprint(projection(List.of(definition("nullable", schema))));

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void eligibility_change_invalidates_provider_checkpoint() {
        ContextUsageEstimator estimator = new ContextUsageEstimator();
        ContextUsageProjection base = projection(List.of());
        SessionId session = new SessionId("session");
        MessageJobId command = new MessageJobId("command");
        SkillCommand parsed = SkillCommand.parse("/skill:review question").orElseThrow();
        List<SessionMessage> audit = List.of(
                new UserMessage(session, new SessionSequence(1), Optional.empty(), Instant.EPOCH, MessageRole.USER, "alice", "first"),
                new UserMessage(session, new SessionSequence(2), Optional.of(command), Instant.EPOCH, MessageRole.USER, "alice", "/skill:review question"),
                new AssistantMessage(session, new SessionSequence(3), Optional.of(new MessageJobId("ordinary")), Instant.EPOCH, MessageRole.ASSISTANT, "response"));
        ContextHistoryView pending = new ContextHistoryView(new ConversationHistory(audit, Map.of(command,
                new SkillCommandState(command, parsed, SkillCommandState.Disposition.PENDING, Optional.empty()))));
        List<SessionMessage> resolved = new ArrayList<>(audit);
        resolved.add(new SkillMessage(session, new SessionSequence(4), Optional.of(command), Instant.EPOCH, MessageRole.SKILL,
                new SkillResource("skill://review/SKILL.md", "a".repeat(64), "instructions")));
        ContextHistoryView accepted = new ContextHistoryView(new ConversationHistory(resolved, Map.of(command,
                new SkillCommandState(command, parsed, SkillCommandState.Disposition.ACCEPTED, Optional.of(new SessionSequence(4))))));
        ContextUsageProjection before = new ContextUsageProjection(base.model(), base.systemPrompt(), List.of(),
                pending.between(0, Long.MAX_VALUE), 0, Optional.empty(), pending.eligibilityFingerprint());
        ContextUsageCheckpoint checkpoint = new ContextUsageCheckpoint(before.model(), 1, new SessionSequence(3), 70, 30, 100,
                estimator.requestShapeFingerprint(before), 0, Instant.EPOCH);
        ContextUsageProjection after = new ContextUsageProjection(before.model(), before.systemPrompt(), before.toolDefinitions(),
                accepted.between(0, Long.MAX_VALUE), 0, Optional.empty(), accepted.eligibilityFingerprint());

        ContextEstimate estimate = estimator.estimate(after, Optional.of(checkpoint));

        assertThat(estimate.basis()).isEqualTo(ContextEstimate.Basis.FULL_ESTIMATE);
        assertThat(estimate.tokens()).isEqualTo(estimator.estimate(after, Optional.empty()).tokens());
    }

    private static ContextUsageProjection projection(List<ToolDefinition> definitions) {
        SessionId sessionId = new SessionId("session-1");
        MessageJobId jobId = new MessageJobId("job-1");
        return new ContextUsageProjection(new ModelDescriptor(new ModelRouteId("google-genai"), "gemini-3.1-flash-lite", 1_048_576),
                "Runtime system prompt", definitions, List.of(
                new UserMessage(sessionId, new SessionSequence(1), Optional.of(jobId), Instant.EPOCH, MessageRole.USER, "alice", "first"),
                new AssistantMessage(sessionId, new SessionSequence(2), Optional.of(jobId), Instant.EPOCH, MessageRole.ASSISTANT, "first reply"),
                new UserMessage(sessionId, new SessionSequence(3), Optional.of(jobId), Instant.EPOCH, MessageRole.USER, "alice", "trailing")),
                0, Optional.empty(), "eligibility");
    }

    private static ToolDefinition definition(String name, Map<String, Object> schema) {
        return new ToolDefinition(new ToolName(name), name + " description", schema);
    }
}
