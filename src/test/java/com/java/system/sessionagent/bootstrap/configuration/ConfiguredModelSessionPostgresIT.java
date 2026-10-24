package com.java.system.sessionagent.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.application.MessageService;
import com.java.system.sessionagent.conversation.application.SessionModelService;
import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.RuntimeMessage;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void reconstructs_configured_models_after_restart_and_honors_exact_job_identity_before_provider_requests() throws Exception {
        List<String> providerRequests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, providerRequests));
        server.start();
        try (ConfiguredModels acceptedModels = configuredModels(server, "accepted-key", null)) {
            ConversationStore acceptedStore = new PostgresConversationStore(dataSource(), Clock.systemUTC(), new ObjectMapper());
            MessageService intake = new MessageService(acceptedStore, acceptedModels, new NoOpConversationTelemetry());
            MessageReceipt old = intake.receive(new IncomingMessage("old", "alice", "old", "old queued"));
            MessageReceipt changed = intake.receive(new IncomingMessage("changed", "alice", "changed", "changed fingerprint"));
            MessageReceipt rotated = intake.receive(new IncomingMessage("rotated", "alice", "rotated", "rotated credential"));
            MessageReceipt missing = intake.receive(new IncomingMessage("missing", "alice", "missing", "missing profile"));
            assertThat(new SessionModelService(acceptedStore, acceptedModels).select(old.sessionId(), "openai-small")).isPresent();

            ConversationStore restartedStore = new PostgresConversationStore(dataSource(), Clock.systemUTC(), new ObjectMapper());
            try (ConfiguredModels recoveredModels = configuredModels(server, "accepted-key", null)) {
                MessageWorkClaim oldClaim = restartedStore.claimNext("restarted", Duration.ofSeconds(30)).orElseThrow();
                processor(restartedStore, recoveredModels).process(oldClaim, () -> true);
                assertThat(oldClaim.modelSelection()).isEqualTo(acceptedModels.findSelection("openai-large").orElseThrow());
                assertThat(providerRequests).hasSize(1);
                assertThat(providerRequests.getFirst()).contains("\"model\":\"gpt-large\"");
            }

            try (ConfiguredModels mismatchModels = configuredModels(server, "accepted-key", 0.2)) {
                MessageWorkClaim changedClaim = restartedStore.claimNext("restarted", Duration.ofSeconds(30)).orElseThrow();
                processor(restartedStore, mismatchModels).process(changedClaim, () -> true);
            }
            assertThat(providerRequests).hasSize(1);
            assertThat(restartedStore.loadHistory(changed.sessionId())).anySatisfy(message ->
                    assertThat(message).isInstanceOfSatisfying(com.java.system.sessionagent.conversation.domain.RuntimeMessage.class,
                            runtime -> assertThat(runtime.code()).isEqualTo("MODEL_UNAVAILABLE")));

            try (ConfiguredModels rotatedModels = configuredModels(server, "rotated-key", null)) {
                MessageWorkClaim rotatedClaim = restartedStore.claimNext("restarted", Duration.ofSeconds(30)).orElseThrow();
                processor(restartedStore, rotatedModels).process(rotatedClaim, () -> true);
            }
            assertThat(providerRequests).hasSize(2);
            assertThat(restartedStore.loadHistory(rotated.sessionId())).extracting(message -> message.sequence().value())
                    .containsExactly(1L, 2L);

            try (ConfiguredModels missingModels = configuredModelsWithOnlySmallProfile(server)) {
                MessageWorkClaim missingClaim = restartedStore.claimNext("restarted", Duration.ofSeconds(30)).orElseThrow();
                processor(restartedStore, missingModels).process(missingClaim, () -> true);
            }
            assertThat(providerRequests).hasSize(2);
            assertThat(restartedStore.loadHistory(missing.sessionId())).anySatisfy(message ->
                    assertThat(message).isInstanceOfSatisfying(com.java.system.sessionagent.conversation.domain.RuntimeMessage.class,
                            runtime -> assertThat(runtime.code()).isEqualTo("MODEL_UNAVAILABLE")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void projects_committed_google_tool_history_across_google_openai_google_with_only_same_turn_thought_signatures() throws Exception {
        ScriptedChatModel google = new ScriptedChatModel(List.of(
                AssistantMessage.builder()
                        .part(new TextPart("Checking.", null, Map.of()))
                        .part(new ToolCallPart(
                                new AssistantMessage.ToolCall("call-fee", "function", "lookup", "{}"),
                                new OpaquePayload("google", "thought_signature", "AQID"),
                                Map.of())).build(),
                new AssistantMessage("x".repeat(6_000)),
                new AssistantMessage("google after openai")));
        ScriptedChatModel openAi = new ScriptedChatModel(List.of(new AssistantMessage("compact prior history"),
                new AssistantMessage("openai after google")));
        try (ConfiguredModels models = configuredScriptedModels(google, openAi, 2_000, "google-a")) {
            ConversationStore store = new PostgresConversationStore(dataSource(), Clock.systemUTC(), new ObjectMapper());
            MessageService intake = new MessageService(store, models, new NoOpConversationTelemetry());
            JobProcessor processor = processor(store, models);

            MessageReceipt first = intake.receive(new IncomingMessage("thread", "alice", "google-tool", "find the fee"));
            processor.process(store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow(), () -> true);
            assertThat(new SessionModelService(store, models).select(first.sessionId(), "openai-b")).isPresent();

            intake.receive(new IncomingMessage("thread", "alice", "openai", "continue with the smaller model"));
            MessageWorkClaim openAiClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
            processor.process(openAiClaim, () -> true);
            assertThat(openAiClaim.modelSelection()).isEqualTo(models.findSelection("openai-b").orElseThrow());
            assertThat(new SessionModelService(store, models).select(first.sessionId(), "google-a")).isPresent();

            intake.receive(new IncomingMessage("thread", "alice", "google-again", "return to google"));
            MessageWorkClaim finalGoogleClaim = store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow();
            processor.process(finalGoogleClaim, () -> true);
            assertThat(finalGoogleClaim.modelSelection()).isEqualTo(models.findSelection("google-a").orElseThrow());

            assertThat(google.prompts()).hasSize(3);
            assertThat(assistantMessages(google.prompts().get(1))).anySatisfy(message ->
                    assertThat(message.getParts()).anySatisfy(part -> assertThat(part.payload()).isNotNull()));
            assertThat(assistantMessages(google.prompts().get(2))).allSatisfy(message ->
                    assertThat(message.getParts()).allSatisfy(part -> assertThat(part.payload()).isNull()));
            assertThat(openAi.prompts()).hasSize(2);
            assertThat(openAi.prompts().getFirst().getInstructions().getFirst().getText()).contains("Summarize the supplied");
            assertThat(openAi.prompts().get(1).getInstructions()).anySatisfy(message ->
                    assertThat(message.getText()).contains("Untrusted historical summary"));
            assertThat(store.loadCompaction(first.sessionId())).hasValueSatisfying(compaction -> {
                assertThat(compaction.reason()).isEqualTo(com.java.system.sessionagent.conversation.domain.ContextCompaction.Reason.THRESHOLD);
                assertThat(compaction.coveredThrough().value()).isEqualTo(4L);
                assertThat(compaction.model().routeId().value()).isEqualTo("openai-b");
            });
            assertThat(store.loadHistory(first.sessionId())).extracting(message -> message.sequence().value())
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
            assertThat(store.loadHistory(first.sessionId()).get(1))
                    .isInstanceOf(com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage.class);
            assertThat(store.loadHistory(first.sessionId()).get(2))
                    .isInstanceOf(com.java.system.sessionagent.conversation.domain.ToolObservation.class);
        }
    }

    @Test
    void rejects_a_too_large_required_suffix_after_selected_model_compaction_without_a_response_request() throws Exception {
        ScriptedChatModel google = new ScriptedChatModel(List.of(new AssistantMessage("prior answer")));
        ScriptedChatModel openAi = new ScriptedChatModel(List.of(new AssistantMessage("compact prior answer")));
        try (ConfiguredModels models = configuredScriptedModels(google, openAi, 512, "google-a")) {
            ConversationStore store = new PostgresConversationStore(dataSource(), Clock.systemUTC(), new ObjectMapper());
            MessageService intake = new MessageService(store, models, new NoOpConversationTelemetry());
            JobProcessor processor = processor(store, models);

            MessageReceipt first = intake.receive(new IncomingMessage("thread", "alice", "first", "answer briefly"));
            processor.process(store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow(), () -> true);
            assertThat(new SessionModelService(store, models).select(first.sessionId(), "openai-b")).isPresent();
            intake.receive(new IncomingMessage("thread", "alice", "oversized", "x".repeat(3_000)));

            processor.process(store.claimNext("worker", Duration.ofSeconds(30)).orElseThrow(), () -> true);

            assertThat(openAi.prompts()).hasSize(1);
            assertThat(openAi.prompts().getFirst().getInstructions().getFirst().getText()).contains("Summarize the supplied");
            assertThat(store.loadCompaction(first.sessionId())).isEmpty();
            assertThat(store.loadHistory(first.sessionId())).anySatisfy(message ->
                    assertThat(message).isInstanceOfSatisfying(RuntimeMessage.class,
                            runtime -> assertThat(runtime.code()).isEqualTo("CONTEXT_TOO_LARGE")));
        }
    }

    private static ConfiguredModels configuredModels(HttpServer server) {
        return configuredModels(server, "test-key", null);
    }

    private static ConfiguredModels configuredModels(HttpServer server, String apiKey, Double temperature) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        RuntimeProperties.ModelProfile large = new RuntimeProperties.ModelProfile("openai", "gpt-large", apiKey,
                32_000, 128, temperature, null, Duration.ofSeconds(2), null, baseUrl);
        RuntimeProperties.ModelProfile small = new RuntimeProperties.ModelProfile("openai", "gpt-small", apiKey,
                512, 128, temperature, null, Duration.ofSeconds(2), null, baseUrl);
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(3, "openai-large",
                Map.of("openai-large", large, "openai-small", small)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(1)));
        return new RuntimeConfiguration().configuredModels(new SystemPrompt(), new NoOpConversationTelemetry(), new ObjectMapper(), properties);
    }

    private static ConfiguredModels configuredModelsWithOnlySmallProfile(HttpServer server) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        RuntimeProperties.ModelProfile small = new RuntimeProperties.ModelProfile("openai", "gpt-small", "test-key",
                512, 128, null, null, Duration.ofSeconds(2), null, baseUrl);
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(3, "openai-small",
                Map.of("openai-small", small)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(1)));
        return new RuntimeConfiguration().configuredModels(new SystemPrompt(), new NoOpConversationTelemetry(), new ObjectMapper(), properties);
    }

    private static ConfiguredModels configuredScriptedModels(
            ScriptedChatModel google,
            ScriptedChatModel openAi,
            long openAiContextWindowTokens,
            String defaultModel) {
        RuntimeProperties.ModelProfile googleProfile = new RuntimeProperties.ModelProfile("google-genai", "gemini-test", "test-key",
                32_000, 128, null, null, Duration.ofSeconds(2), null, null);
        RuntimeProperties.ModelProfile openAiProfile = new RuntimeProperties.ModelProfile("openai", "gpt-test", "test-key",
                openAiContextWindowTokens, 128, null, null, Duration.ofSeconds(2), null, "http://127.0.0.1:1/v1");
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(3, defaultModel,
                Map.of("google-a", googleProfile, "openai-b", openAiProfile)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(1)));
        return RuntimeConfiguration.assembleConfiguredModels(new SystemPrompt(), new NoOpConversationTelemetry(), new ObjectMapper(), properties,
                profile -> new RuntimeConfiguration.CreatedChatModel("google-genai".equals(profile.profile().provider()) ? google : openAi,
                        Optional.empty()));
    }

    private static List<AssistantMessage> assistantMessages(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .toList();
    }

    private static JobProcessor processor(ConversationStore store, ConfiguredModels models) {
        return new JobProcessor(store, models, catalog(), Clock.systemUTC(), 3,
                new MessageJobRetryPolicy(3, Duration.ofSeconds(1)), new NoOpConversationTelemetry());
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
        if (request.contains("\"model\":\"gpt-large\"") && request.contains("find the fee")
                && !request.contains("\"role\":\"tool\"")) {
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

    private static final class ScriptedChatModel implements ChatModel {
        private final List<AssistantMessage> responses;
        private final List<Prompt> prompts = new ArrayList<>();
        private int nextResponse;

        private ScriptedChatModel(List<AssistantMessage> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (nextResponse >= responses.size()) {
                throw new AssertionError("Unexpected provider request");
            }
            AssistantMessage response = responses.get(nextResponse);
            nextResponse++;
            return new ChatResponse(List.of(new Generation(response)), ChatResponseMetadata.builder().build());
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private List<Prompt> prompts() {
            return List.copyOf(prompts);
        }
    }
}
