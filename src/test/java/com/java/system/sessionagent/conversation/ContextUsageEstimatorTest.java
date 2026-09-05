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
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContextUsageEstimatorTest {

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
    void fingerprints_opaque_tool_schemas_that_contain_json_null_values() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("default", null);
        schema.put("type", "string");
        ContextUsageEstimator estimator = new ContextUsageEstimator();

        String fingerprint = estimator.requestShapeFingerprint(projection(List.of(definition("nullable", schema))));

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]+");
    }

    private static ContextUsageProjection projection(List<ToolDefinition> definitions) {
        SessionId sessionId = new SessionId("session-1");
        MessageJobId jobId = new MessageJobId("job-1");
        return new ContextUsageProjection(new ModelDescriptor(new ModelRouteId("google-genai"), "gemini-3.1-flash-lite", 1_048_576),
                "Runtime system prompt", definitions, List.of(
                new UserMessage(sessionId, new SessionSequence(1), Optional.of(jobId), Instant.EPOCH, MessageRole.USER, "alice", "first"),
                new AssistantMessage(sessionId, new SessionSequence(2), Optional.of(jobId), Instant.EPOCH, MessageRole.ASSISTANT, "first reply"),
                new UserMessage(sessionId, new SessionSequence(3), Optional.of(jobId), Instant.EPOCH, MessageRole.USER, "alice", "trailing")), 0);
    }

    private static ToolDefinition definition(String name, Map<String, Object> schema) {
        return new ToolDefinition(new ToolName(name), name + " description", schema);
    }
}
