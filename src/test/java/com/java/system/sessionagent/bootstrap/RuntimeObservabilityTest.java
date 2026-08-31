package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.RuntimeMessageCode;
import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.domain.RepositoryId;
import io.micrometer.core.instrument.Timer;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RuntimeObservabilityTest {

    @Test
    void recordsContentFreeBoundedTelemetryWithModelAndToolDurations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerConversationTelemetry telemetry = new MicrometerConversationTelemetry(registry);

        telemetry.intake("ACCEPTED");
        telemetry.job("CLAIMED");
        telemetry.model("SUCCESS", Optional.of("STOP"), new ModelUsage(5, 3, 8, true), Duration.ofMillis(25));
        telemetry.tool("list_repositories", "SUCCESS", Duration.ofMillis(10));
        telemetry.feedback("INVALID_TOOL_INPUT");
        telemetry.retry("DEPENDENCY", Duration.ofSeconds(1));
        telemetry.model("provider-secret", Optional.of("provider-payload"), new ModelUsage(0, 0, 0, false),
                Duration.ofMillis(5));
        telemetry.tool("repo-private", "provider-secret", Duration.ofMillis(5));

        String tags = registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue()).reduce("", (left, right) -> left + "\n" + right);
        assertThat(tags).doesNotContain("repo-private", "revision-private", "provider-secret", "provider-payload",
                "apiKey", "input-private", "output-private", "prompt-private", "completion-private");
        assertThat(registry.find("session_agent.tool").counter()).isNotNull();
        assertThat(registry.find("session_agent.tool").tags("tool", "OTHER", "outcome", "OTHER").counter()).isNotNull();
        Timer modelDuration = registry.find("session_agent.model.duration").tags(
                "outcome", "SUCCESS", "category", "STOP", "usage_available", "true").timer();
        assertThat(modelDuration).isNotNull();
        assertThat(modelDuration.count()).isEqualTo(1);
        assertThat(modelDuration.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(25.0);
        Timer toolDuration = registry.find("session_agent.tool.duration").tags(
                "tool", "list_repositories", "outcome", "SUCCESS").timer();
        assertThat(toolDuration).isNotNull();
        assertThat(toolDuration.count()).isEqualTo(1);
        assertThat(toolDuration.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(10.0);
        assertThat(registry.find("session_agent.tool.duration").tags("tool", "OTHER", "outcome", "OTHER").timer())
                .isNotNull();
    }

    @Test
    void preserves_final_runtime_feedback_codes_and_bounds_unknown_codes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerConversationTelemetry telemetry = new MicrometerConversationTelemetry(registry);

        for (RuntimeMessageCode code : RuntimeMessageCode.values()) {
            telemetry.feedback(code.name());
        }
        telemetry.feedback("provider-private-code");

        for (RuntimeMessageCode code : RuntimeMessageCode.values()) {
            assertThat(registry.find("session_agent.feedback").tag("code", code.name()).counter()).isNotNull();
        }
        assertThat(registry.find("session_agent.feedback").tag("code", "OTHER").counter()).isNotNull();
    }

    @Test
    void observesSemanticHttpFailuresWithBoundedStatusAndOutcomeAtTheExternalBoundary() {
        ObservationRegistry observations = ObservationRegistry.create();
        List<ClientRequestObservationContext> stopped = new ArrayList<>();
        observations.observationConfig().observationHandler(new ObservationHandler<ClientRequestObservationContext>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ClientRequestObservationContext;
            }

            @Override
            public void onStop(ClientRequestObservationContext context) {
                stopped.add(context);
            }
        });
        RestClient.Builder builder = RestClient.builder().baseUrl("http://semantic.test").observationRegistry(observations);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://semantic.test/v1/repositories")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        SemanticRepositoryClient semanticClient = new SemanticRepositoryClient(builder.build());

        assertThatThrownBy(semanticClient::listRepositories)
                .isInstanceOf(SemanticFailure.class);
        server.verify();

        String tags = new DefaultClientRequestObservationConvention().getLowCardinalityKeyValues(stopped.getFirst()).stream()
                .map(tag -> tag.getKey() + "=" + tag.getValue()).reduce("", (left, right) -> left + "\n" + right);
        assertThat(tags).contains("status=503", "outcome=SERVER_ERROR");
        assertThat(tags).doesNotContain("repo-1", "http://semantic.test");
    }
}
