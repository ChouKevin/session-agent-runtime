package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessageCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeObservabilityTest {

    @Test
    void bounds_tool_names_to_portable_or_other() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerConversationTelemetry telemetry = new MicrometerConversationTelemetry(registry);

        telemetry.tool("semantic_search_code", "SUCCESS", Duration.ofMillis(10));
        telemetry.tool("private tool name", "provider-secret", Duration.ofMillis(5));
        telemetry.model("SUCCESS", Optional.of("STOP"), new ModelUsage(5, 3, 8, true), Duration.ofMillis(25));

        assertThat(registry.find("session_agent.tool").tags("tool", "PORTABLE", "outcome", "SUCCESS").counter()).isNotNull();
        assertThat(registry.find("session_agent.tool").tags("tool", "OTHER", "outcome", "OTHER").counter()).isNotNull();
        String tags = registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue()).reduce("", (left, right) -> left + "\n" + right);
        assertThat(tags).doesNotContain("semantic_search_code", "private tool name", "provider-secret");
    }

    @Test
    void preserves_runtime_feedback_codes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerConversationTelemetry telemetry = new MicrometerConversationTelemetry(registry);

        for (RuntimeMessageCode code : RuntimeMessageCode.values()) {
            telemetry.feedback(code.name());
            assertThat(registry.find("session_agent.feedback").tag("code", code.name()).counter()).isNotNull();
        }
    }
}
