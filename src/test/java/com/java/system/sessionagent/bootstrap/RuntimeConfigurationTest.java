package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RuntimeConfigurationTest {

    @Test
    void binds_loopback_and_generic_mcp_defaults() throws Exception {
        String configuration = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(configuration).contains("server:\n  address: 127.0.0.1", "connections: {}",
                "max-model-calls-per-message: ${SESSION_AGENT_MAX_MODEL_CALLS_PER_MESSAGE:12}");
        assertThat(configuration).doesNotContain("semantic:", "SEMANTIC_");
    }

    @Test
    void maps_worker_and_retry_policies() {
        RuntimeProperties.Worker worker = new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60));
        RuntimeConfiguration configuration = new RuntimeConfiguration();
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "google-genai"), worker);

        WorkerProperties workerProperties = configuration.workerProperties(properties);
        MessageJobRetryPolicy retryPolicy = configuration.messageJobRetryPolicy(properties);

        assertThat(workerProperties.renewalInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(retryPolicy.maximumBackoff()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejects_unsafe_worker_and_datasource_configuration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Worker(
                Duration.ZERO, Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Datasource(
                "jdbc:postgresql://localhost:5432/session_agent", " "));
    }
}
