package com.java.system.sessionagent.bootstrap;

import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.semantic.SemanticFailure;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.input.IncomingCallGraphInput;
import com.java.system.sessionagent.worker.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeConfigurationTest {

    @Test
    void binds_the_runtime_to_loopback_by_default() throws Exception {
        String configuration = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(configuration).contains("server:\n  address: 127.0.0.1");
        assertThat(configuration).contains("response-timeout: ${SEMANTIC_RESPONSE_TIMEOUT:120s}");
    }

    @Test
    void mapsDeployDefaultsToTheActualWorkerAndRetryPolicies() {
        RuntimeProperties.Worker worker = new RuntimeProperties.Worker(Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60));
        RuntimeConfiguration configuration = new RuntimeConfiguration();

        WorkerProperties workerProperties = configuration.workerProperties(new RuntimeProperties(
                semanticProperties("http://localhost:8080"),
                new RuntimeProperties.Model("gemini-3.1-flash-lite"), worker));
        MessageJobRetryPolicy retryPolicy = configuration.messageJobRetryPolicy(new RuntimeProperties(
                semanticProperties("http://localhost:8080"),
                new RuntimeProperties.Model("gemini-3.1-flash-lite"), worker));

        assertThat(workerProperties.claimDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(workerProperties.renewalInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(retryPolicy.transientRetries()).isEqualTo(3);
        assertThat(retryPolicy.maximumBackoff()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void sendsConfiguredSemanticTokenOnlyAsTheRequiredApiTokenHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/repositories", this::assertSemanticTokenHeader);
            server.start();
            RuntimeConfiguration configuration = new RuntimeConfiguration();
            RuntimeProperties properties = new RuntimeProperties(
                    new RuntimeProperties.Semantic("http://127.0.0.1:" + server.getAddress().getPort(), "configured-token", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                    new RuntimeProperties.Model("gemini-3.1-flash-lite"),
                    new RuntimeProperties.Worker(Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
            RestClient client = configuration.semanticRestClient(properties, io.micrometer.observation.ObservationRegistry.NOOP);

            String body = client.get().uri("/v1/repositories").retrieve().body(String.class);

            assertThat(body).isEqualTo("[]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void turnsAStalledSemanticSourceResponseIntoTransientFailureWithinConfiguredTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/repositories/repository-a", exchange -> {
                byte[] response = "{\"repoId\":\"repository-a\",\"mode\":\"REMOTE\",\"displayName\":\"Repository A\","
                        .concat("\"currentBranch\":\"main\",\"currentRevision\":\"revision-1\",\"cloned\":true}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.createContext("/v1/analyses/call-graphs/incoming", exchange -> {
                try {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 128);
                    exchange.getResponseBody().flush();
                    Thread.sleep(Duration.ofMillis(500));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            RuntimeConfiguration configuration = new RuntimeConfiguration();
            RuntimeProperties properties = new RuntimeProperties(
                    new RuntimeProperties.Semantic("http://127.0.0.1:" + server.getAddress().getPort(), "configured-token", Duration.ofSeconds(1), Duration.ofMillis(50)),
                    new RuntimeProperties.Model("gemini-3.1-flash-lite"),
                    new RuntimeProperties.Worker(Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
            RestClient restClient = configuration.semanticRestClient(
                    properties, io.micrometer.observation.ObservationRegistry.NOOP);
            SemanticSourceClient semanticClient = configuration.semanticSourceClient(restClient);
            assertThatThrownBy(() -> semanticClient.incomingCallGraph(
                    new IncomingCallGraphInput("repository-a", "revision-a", "com.example", "OrderService",
                            "src/main/java/com/example/OrderService.java", "cancel", List.of("java.lang.String"), 2, 100)))
                    .isInstanceOf(SemanticFailure.class)
                    .satisfies(exception -> assertThat(((SemanticFailure) exception).kind())
                            .isEqualTo(SemanticFailure.Kind.SEMANTIC_INDEX_UNAVAILABLE));
        } finally {
            server.stop(0);
        }
    }

    private RuntimeProperties.Semantic semanticProperties(String baseUrl) {
        return new RuntimeProperties.Semantic(baseUrl, "test-semantic-token", Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    private void assertSemanticTokenHeader(HttpExchange exchange) throws IOException {
        try {
            assertThat(exchange.getRequestHeaders().getFirst("X-Api-Token")).isEqualTo("configured-token");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        } finally {
            exchange.close();
        }
    }

    @Test
    void rejectsUnsafeRuntimeWorkerDurationsBeforeWorkersAreConstructed() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Worker(
                Duration.ZERO, Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ZERO, 3, Duration.ofSeconds(60)));
    }

    @Test
    void rejectsBlankRequiredSemanticAndDatasourceSecretsAtConfigurationBoundary() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Semantic(
                "http://localhost:8080", " ", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RuntimeProperties.Datasource(
                "jdbc:postgresql://localhost:5432/session_agent", " "));
    }

}
