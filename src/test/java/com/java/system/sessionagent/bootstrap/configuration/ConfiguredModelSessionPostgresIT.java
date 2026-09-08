package com.java.system.sessionagent.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.SessionModelService;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.storage.adapter.postgres.PostgresConversationStore;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCatalog;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolOutput;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfiguredModelSessionPostgresIT {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeEach
    void migrate() {
        POSTGRES.start();
        flyway().clean();
        flyway().migrate();
    }

    @AfterEach
    void clean() {
        flyway().clean();
    }

    @Test
    void binds_each_accepted_job_to_its_selected_adapter_and_projects_tool_history_for_a_smaller_model() throws Exception {
        List<String> providerRequests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, providerRequests));
        server.start();
        try (ConfiguredModels models = configuredModels(server)) {
            ConversationStore store = new PostgresConversationStore(dataSource(), Clock.systemUTC(), new ObjectMapper());
            MessageService intake = new MessageService(store, models, new NoOpConversationTelemetry());
            JobProcessor processor = new JobProcessor(store, models, catalog(), Clock.systemUTC(), 3,
                    new MessageJobRetryPolicy(3, Duration.ofSeconds(1)), new NoOpConversationTelemetry());

            MessageReceipt first = intake.receive(new IncomingMessage("thread", "alice", "source-a", "find the fee"));
            MessageWorkClaim firstClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
            processor.process(firstClaim, () -> true);

            assertThat(new SessionModelService(store, models).select(first.sessionId(), "openai-small"))
                    .hasValueSatisfying(view -> assertThat(view.model()).isEqualTo("openai-small"));
            MessageReceipt second = intake.receive(new IncomingMessage("thread", "alice", "source-b", "what was found?"));
            MessageWorkClaim secondClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
            processor.process(secondClaim, () -> true);

            assertThat(firstClaim.modelSelection()).isEqualTo(models.findSelection("openai-large").orElseThrow());
            assertThat(secondClaim.modelSelection()).isEqualTo(models.findSelection("openai-small").orElseThrow());
            assertThat(models.resolve(secondClaim.modelSelection()).orElseThrow().descriptor().contextWindowTokens()).isEqualTo(512L);
            assertThat(providerRequests).hasSize(3);
            assertThat(providerRequests.get(0)).contains("\"model\":\"gpt-large\"");
            assertThat(providerRequests.get(1)).contains("\"model\":\"gpt-large\"", "\"tool_call_id\":\"call-fee\"");
            assertThat(providerRequests.get(2)).contains("\"model\":\"gpt-small\"", "\"id\":\"call-fee\"",
                    "\"tool_call_id\":\"call-fee\"", "fee is 9");
            assertThat(store.loadHistory(first.sessionId())).extracting(message -> message.sequence().value())
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        } finally {
            server.stop(0);
        }
    }

    private static ConfiguredModels configuredModels(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        RuntimeProperties.ModelProfile large = new RuntimeProperties.ModelProfile("openai", "gpt-large", "test-key",
                32_000, 128, null, null, Duration.ofSeconds(2), null, baseUrl);
        RuntimeProperties.ModelProfile small = new RuntimeProperties.ModelProfile("openai", "gpt-small", "test-key",
                512, 128, null, null, Duration.ofSeconds(2), null, baseUrl);
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(3, "openai-large",
                Map.of("openai-large", large, "openai-small", small)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(1)));
        return new RuntimeConfiguration().configuredModels(new SystemPrompt(), new NoOpConversationTelemetry(), new ObjectMapper(), properties);
    }

    private static ToolCatalog catalog() {
        ToolBinding lookup = new ToolBinding(new ToolDefinition(new ToolName("lookup"), "Look up a fee", Map.of()),
                arguments -> new ToolOutput(false, Map.of("result", "fee is 9")));
        return () -> new ToolSnapshot(List.of(lookup));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, List<String> requests) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(request);
        String response;
        if (request.contains("\"model\":\"gpt-large\"") && !request.contains("\"role\":\"tool\"")) {
            response = """
                    {"id":"large-tool","object":"chat.completion","created":0,"model":"gpt-large",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"Checking.","tool_calls":[{"id":"call-fee","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                    """;
        } else {
            String model = request.contains("\"model\":\"gpt-small\"") ? "gpt-small" : "gpt-large";
            response = """
                    {"id":"text","object":"chat.completion","created":0,"model":"%s",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"Done."},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                    """.formatted(model);
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource()).cleanDisabled(false).load();
    }
}
