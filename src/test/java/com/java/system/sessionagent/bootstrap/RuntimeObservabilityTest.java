package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.domain.RepositoryId;
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
    void emitsOnlyLowCardinalityTagsAndNeverPersistsSensitiveToolValuesInMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerConversationTelemetry telemetry = new MicrometerConversationTelemetry(registry);

        telemetry.intake("ACCEPTED");
        telemetry.job("CLAIMED");
        telemetry.model("SUCCESS", Optional.of("REPLY"), new ModelUsage(5, 3, 8, true));
        telemetry.tool("list_repositories", "SUCCESS", Optional.of("repo-private"), Optional.of("revision-private"));
        telemetry.feedback("INVALID_TOOL_INPUT");
        telemetry.retry("DEPENDENCY", Duration.ofSeconds(1));
        telemetry.tool("repo-private", "provider-secret", Optional.of("repo-private"), Optional.of("revision-private"));

        String tags = registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey() + "=" + tag.getValue()).reduce("", (left, right) -> left + "\n" + right);
        assertThat(tags).doesNotContain("repo-private", "revision-private", "apiKey");
        assertThat(registry.find("session_agent.tool").counter()).isNotNull();
        assertThat(registry.find("session_agent.tool").tags("tool", "OTHER", "outcome", "OTHER").counter()).isNotNull();
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
        server.expect(requestTo("http://semantic.test/v1/repositories/repo-1")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        SemanticRepositoryClient semanticClient = new SemanticRepositoryClient(builder.build());

        assertThatThrownBy(() -> semanticClient.currentRevision(new RepositoryId("repo-1")))
                .isInstanceOf(SemanticFailure.class);
        server.verify();

        String tags = new DefaultClientRequestObservationConvention().getLowCardinalityKeyValues(stopped.getFirst()).stream()
                .map(tag -> tag.getKey() + "=" + tag.getValue()).reduce("", (left, right) -> left + "\n" + right);
        assertThat(tags).contains("status=503", "outcome=SERVER_ERROR");
        assertThat(tags).doesNotContain("repo-1", "http://semantic.test");
    }
}
