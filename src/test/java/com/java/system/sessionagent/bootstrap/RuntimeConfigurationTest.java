package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.port.out.RepositoryRevisionReader;
import com.java.system.sessionagent.conversation.port.out.RevisionLookup;
import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

class RuntimeConfigurationTest {

    @Test
    void binds_the_runtime_to_loopback_by_default() throws Exception {
        String configuration = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(configuration).contains("server:\n  address: 127.0.0.1");
    }

    @Test
    void mapsDeployDefaultsToTheActualWorkerAndRetryPolicies() {
        RuntimeProperties.Worker worker = new RuntimeProperties.Worker(Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60));
        RuntimeConfiguration configuration = new RuntimeConfiguration();

        WorkerProperties workerProperties = configuration.workerProperties(new RuntimeProperties(
                new RuntimeProperties.Semantic("http://localhost:8080", ""),
                new RuntimeProperties.Model("gemini-3.1-flash-lite"), worker));
        MessageJobRetryPolicy retryPolicy = configuration.messageJobRetryPolicy(new RuntimeProperties(
                new RuntimeProperties.Semantic("http://localhost:8080", ""),
                new RuntimeProperties.Model("gemini-3.1-flash-lite"), worker));

        assertThat(workerProperties.claimDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(workerProperties.renewalInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(retryPolicy.transientRetries()).isEqualTo(3);
        assertThat(retryPolicy.maximumBackoff()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rejectsUnsafeRuntimeWorkerDurationsBeforeWorkersAreConstructed() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Worker(
                Duration.ZERO, Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ZERO, 3, Duration.ofSeconds(60)));
    }

    @Test
    void closesEverySemanticFailureAtTheConversationRevisionBoundary() {
        SemanticRepositoryClient client = mock(SemanticRepositoryClient.class);
        RuntimeConfiguration configuration = new RuntimeConfiguration();
        RepositoryRevisionReader reader = configuration.repositoryRevisionReader(client);

        doThrow(SemanticFailure.transientFailure(java.util.Optional.empty())).when(client).currentRevision(any());
        assertThat(reader.read("repo-a")).isInstanceOf(RevisionLookup.TemporaryFailure.class);
        doThrow(SemanticFailure.forbidden()).when(client).currentRevision(any());
        assertThat(reader.read("repo-a")).isInstanceOf(RevisionLookup.Forbidden.class);
        doThrow(SemanticFailure.unknownRepository()).when(client).currentRevision(any());
        assertThat(reader.read("repo-a")).isInstanceOf(RevisionLookup.UnknownRepository.class);
        doThrow(SemanticFailure.revisionChanged()).when(client).currentRevision(any());
        assertThat(reader.read("repo-a")).isInstanceOf(RevisionLookup.InvalidResponse.class);
        doThrow(SemanticFailure.invalidResponse()).when(client).currentRevision(any());
        assertThat(reader.read("repo-a")).isInstanceOf(RevisionLookup.InvalidResponse.class);
        doThrow(new IllegalStateException("raw semantic detail")).when(client).currentRevision(any());
        assertThat(reader.read("repo-a")).isInstanceOf(RevisionLookup.InvalidResponse.class);
    }
}
