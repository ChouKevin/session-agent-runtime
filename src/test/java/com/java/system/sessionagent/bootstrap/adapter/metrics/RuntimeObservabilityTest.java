package com.java.system.sessionagent.bootstrap.adapter.metrics;

import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessageCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeObservabilityTest {

    @Test
    void bounds_tool_names_to_portable_or_other() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerConversationTelemetry telemetry = new MicrometerConversationTelemetry(registry);

        telemetry.tool("semantic_search_code", "SUCCESS", Duration.ofMillis(10));
        telemetry.tool("private tool name", "provider-secret", Duration.ofMillis(5));
        telemetry.model("SUCCESS", Optional.of("STOP"), new ModelUsage(5, 3, 8, true), Duration.ofMillis(25));

        assertThat(registry.get("session_agent.tool").tags("tool", "PORTABLE", "outcome", "SUCCESS").counter().count()).isEqualTo(1);
        assertThat(registry.get("session_agent.tool").tags("tool", "OTHER", "outcome", "OTHER").counter().count()).isEqualTo(1);
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
            assertThat(registry.get("session_agent.feedback").tag("code", code.name()).counter().count()).isEqualTo(1);
        }
    }

    @Test
    void records_cache_state_once_and_keeps_cached_tokens_out_of_existing_totals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerConversationTelemetry telemetry = new MicrometerConversationTelemetry(registry);

        telemetry.model("SUCCESS", Optional.of("RESPONSE"), new ModelUsage(5, 3, 8, true, OptionalLong.of(4)),
                Duration.ofMillis(25));
        telemetry.model("SUCCESS", Optional.of("RESPONSE"), new ModelUsage(5, 3, 8, true, OptionalLong.of(0)),
                Duration.ofMillis(25));
        telemetry.model("SUCCESS", Optional.of("RESPONSE"), new ModelUsage(0, 0, 0, false),
                Duration.ofMillis(25));

        assertThat(registry.get("session_agent.model.cache_usage").tag("state", "HIT").counter().count()).isEqualTo(1);
        assertThat(registry.get("session_agent.model.cache_usage").tag("state", "ZERO").counter().count()).isEqualTo(1);
        assertThat(registry.get("session_agent.model.cache_usage").tag("state", "UNKNOWN").counter().count()).isEqualTo(1);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "cached_input").counter().count()).isEqualTo(4);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "prompt").counter().count()).isEqualTo(10);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "completion").counter().count()).isEqualTo(6);
        assertThat(registry.get("session_agent.model.tokens").tag("kind", "total").counter().count()).isEqualTo(16);
    }
}
