package com.java.system.sessionagent.bootstrap.configuration;

import com.java.system.sessionagent.conversation.domain.ModelCallObservation;
import com.java.system.sessionagent.conversation.domain.CacheTokenUsage;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ContextCompactionRequest;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.bootstrap.adapter.metrics.MicrometerConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolCapability;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiConfiguredModelTest {
    @ParameterizedTest(name = "OpenAI raw usage: {0}")
    @MethodSource("openAiAccountingCases")
    void records_only_explicit_valid_input_cache_pairs_without_extra_http_requests(
            String scenario, String usageJson, Optional<CacheTokenUsage> expected) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String response = """
                {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"gpt-5-mini",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello."},"finish_reason":"stop"}],
                 "usage":%s}
                """.formatted(usageJson);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, new AtomicReference<>(),
                new AtomicReference<>(), requests, response));
        server.start();
        try (ConfiguredModels models = configuredModels(server)) {
            List<ModelCallObservation> observed = new ArrayList<>();
            ModelCallResult result = models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER),
                    reservations::incrementAndGet, observed::add);
            assertThat(result.reply()).as(scenario).isEqualTo(new ModelReply.Text("Hello."));
            assertThat(observed).as(scenario).containsExactly(new ModelCallObservation(1, expected));
            if ("omitted input".equals(scenario) || "missing completion and total".equals(scenario)) {
                assertThat(result.usage().available()).as("cache accounting must not invent ordinary usage").isFalse();
            }
            assertThat(reservations).hasValue(1);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private static Stream<Arguments> openAiAccountingCases() {
        return Stream.of(
                Arguments.of("cache hit", "{\"prompt_tokens\":8,\"completion_tokens\":2,\"total_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":3}}", Optional.of(new CacheTokenUsage(8, 3))),
                Arguments.of("explicit cache zero", "{\"prompt_tokens\":8,\"completion_tokens\":2,\"total_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":0}}", Optional.of(new CacheTokenUsage(8, 0))),
                Arguments.of("omitted cache", "{\"prompt_tokens\":8,\"completion_tokens\":2,\"total_tokens\":10}", Optional.empty()),
                Arguments.of("omitted input", "{\"completion_tokens\":2,\"total_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":0}}", Optional.empty()),
                Arguments.of("cache exceeds input", "{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":7}}", Optional.empty()),
                Arguments.of("negative input", "{\"prompt_tokens\":-1,\"completion_tokens\":2,\"total_tokens\":1,\"prompt_tokens_details\":{\"cached_tokens\":0}}", Optional.empty()),
                Arguments.of("negative cache", "{\"prompt_tokens\":8,\"completion_tokens\":2,\"total_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":-1}}", Optional.empty()),
                Arguments.of("missing completion and total", "{\"prompt_tokens\":8,\"prompt_tokens_details\":{\"cached_tokens\":3}}", Optional.of(new CacheTokenUsage(8, 3))));
    }

    @ParameterizedTest(name = "OpenAI cached input {0}")
    @MethodSource("openAiCacheUsageCases")
    void maps_openai_provider_cache_usage_to_common_telemetry(
            String scenario,
            String cacheDetails,
            String expectedState,
            OptionalLong expectedCachedInputTokens) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, new AtomicReference<>(),
                new AtomicReference<>(), providerRequests, openAiCacheUsageResponse(cacheDetails)));
        server.start();
        try (ConfiguredModels models = configuredModels(server, new MicrometerConversationTelemetry(registry))) {
            ModelCallResult result = models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), reservations::incrementAndGet, ignored -> { });

            assertThat(result.usage()).as(scenario)
                    .isEqualTo(new ModelUsage(8, 2, 10, true, expectedCachedInputTokens));
            assertThat(reservations).hasValue(1);
            assertThat(providerRequests).hasValue(1);
            assertThat(registry.get("session_agent.model.cache_usage").tag("state", expectedState)
                    .counter().count()).isEqualTo(1);
            assertThat(registry.get("session_agent.model.tokens").tag("kind", "prompt").counter().count())
                    .isEqualTo(8);
            assertThat(registry.get("session_agent.model.tokens").tag("kind", "completion").counter().count())
                    .isEqualTo(2);
            assertThat(registry.get("session_agent.model.tokens").tag("kind", "total").counter().count())
                    .isEqualTo(10);
            if (expectedCachedInputTokens.isPresent()) {
                assertThat(registry.get("session_agent.model.tokens").tag("kind", "cached_input").counter().count())
                        .isEqualTo(expectedCachedInputTokens.getAsLong());
            } else {
                assertThat(registry.find("session_agent.model.tokens").tags("kind", "cached_input").counter())
                        .isNull();
            }
        } finally {
            server.stop(0);
        }
    }

    private static Stream<Arguments> openAiCacheUsageCases() {
        return Stream.of(
                Arguments.of("hit", ",\"prompt_tokens_details\":{\"cached_tokens\":4}", "HIT", OptionalLong.of(4)),
                Arguments.of("zero", ",\"prompt_tokens_details\":{\"cached_tokens\":0}", "ZERO", OptionalLong.of(0)),
                Arguments.of("absent", "", "UNKNOWN", OptionalLong.empty()));
    }

    @Test
    void maps_a_configured_openai_text_response_to_the_provider_neutral_port() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, requestPath, requestBody, providerRequests, """
                {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"gpt-5-mini",
                "choices":[{"index":0,"message":{"role":"assistant","content":"Hello."},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                """));
        server.start();
        try (ConfiguredModels models = configuredModels(server, Duration.ofSeconds(2), 0.25, 0.8)) {
            ModelCallResult result = models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), reservations::incrementAndGet, ignored -> { });

            assertThat(result.reply()).isEqualTo(new ModelReply.Text("Hello."));
            assertThat(reservations).hasValue(1);
            assertThat(providerRequests).hasValue(1);
            assertThat(requestPath).hasValue("/v1/chat/completions");
            assertThat(requestBody.get()).contains("\"model\":\"gpt-5-mini\"", "\"store\":false",
                    "\"max_completion_tokens\":1234", "\"temperature\":0.25", "\"top_p\":0.8");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maps_a_configured_openai_summary_without_forcing_sampling_options() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, requestPath, requestBody, providerRequests,
                textResponse("Concise session summary.")));
        server.start();
        UserMessage history = new UserMessage(new SessionId("session-1"), new SessionSequence(1),
                Optional.of(new MessageJobId("job-1")), Instant.EPOCH, MessageRole.USER, "user", "History.");
        try (ConfiguredModels models = configuredModels(server)) {
            String summary = models.defaultConversationModel().summarize(
                    new ContextCompactionRequest(Optional.empty(), List.of(history)), reservations::incrementAndGet, ignored -> { });

            assertThat(summary).isEqualTo("Concise session summary.");
            assertThat(reservations).hasValue(1);
            assertThat(providerRequests).hasValue(1);
            assertThat(requestPath).hasValue("/v1/chat/completions");
            assertThat(requestBody.get()).contains("\"model\":\"gpt-5-mini\"", "\"store\":false",
                    "\"max_completion_tokens\":1234");
            assertThat(requestBody.get()).doesNotContain("\"temperature\"", "\"top_p\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maps_a_single_openai_rate_limit_response_to_a_transient_failure_without_sdk_retry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] response = "{\"error\":{\"message\":\"rate limited\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (ConfiguredModels models = configuredModels(server)) {
            assertThatThrownBy(() -> models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { }))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TRANSIENT));
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maps_openai_five_hundred_response_to_a_transient_failure_without_sdk_retry() throws Exception {
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = errorServer(500, "provider unavailable", providerRequests);
        server.start();
        try (ConfiguredModels models = configuredModels(server)) {
            assertThatThrownBy(() -> models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { }))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TRANSIENT));
            assertThat(providerRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maps_openai_bad_request_with_a_misleading_server_error_number_to_a_terminal_failure() throws Exception {
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = errorServer(400, "invalid value 500 for parameter", providerRequests);
        server.start();
        try (ConfiguredModels models = configuredModels(server)) {
            assertThatThrownBy(() -> models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { }))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL));
            assertThat(providerRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maps_openai_terminal_and_context_limit_responses_without_extra_provider_requests() throws Exception {
        AtomicInteger terminalRequests = new AtomicInteger();
        HttpServer terminalServer = errorServer(401, "invalid API key", terminalRequests);
        terminalServer.start();
        try (ConfiguredModels models = configuredModels(terminalServer)) {
            assertThatThrownBy(() -> models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { }))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TERMINAL));
            assertThat(terminalRequests).hasValue(1);
        } finally {
            terminalServer.stop(0);
        }

        AtomicInteger contextRequests = new AtomicInteger();
        HttpServer contextServer = errorServer(400,
                "This model's maximum context length is 100 tokens, however you requested 101 tokens", contextRequests);
        contextServer.start();
        try (ConfiguredModels models = configuredModels(contextServer)) {
            assertThatThrownBy(() -> models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { }))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.CONTEXT_TOO_LARGE));
            assertThat(contextRequests).hasValue(1);
        } finally {
            contextServer.stop(0);
        }
    }

    @Test
    void maps_openai_request_timeout_to_a_transient_failure_without_sdk_retry() throws Exception {
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            providerRequests.incrementAndGet();
            try {
                Thread.sleep(300);
                byte[] response = textResponse("Too late.").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (ConfiguredModels models = configuredModels(server, Duration.ofMillis(50))) {
            assertThatThrownBy(() -> models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { }))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TRANSIENT));
            assertThat(providerRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void replays_openai_tool_text_reasoning_and_extra_properties_without_changing_opaque_tool_schema() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, new AtomicReference<>(), requestBody,
                providerRequests, providerRequests.get() == 0 ? """
                {"id":"chatcmpl-2","object":"chat.completion","created":0,"model":"gpt-5-mini",
                "choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"Need a lookup.","content":"Checking.","tool_calls":[{"id":"call-lookup","type":"function","function":{"name":"lookup","arguments":"{\\"query\\":\\"fee\\"}"},"extra_content":{"google":{"thought_signature":"AQID"}}}]},"finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                """ : textResponse("The fee is 9.")));
        server.start();
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query"), "additionalProperties", false);
        ToolBinding tool = new ToolBinding(ToolDefinition.fromExposedName("lookup", "Look up a fee", schema),
                (context, arguments) -> new com.java.system.sessionagent.tool.port.ToolOutput(false, Map.of()), ToolCapability.ORDINARY);
        try (ConfiguredModels models = configuredModels(server)) {
            ModelCallResult result = models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of(tool)), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { });

            assertThat(result.reply()).isEqualTo(new ModelReply.UseTools(Optional.of("Checking."), List.of(
                    new ToolRequest(new ToolCallId("call-lookup"), new ToolName("lookup"), Map.of("query", "fee")))));
            assertThat(providerRequests).hasValue(1);
            assertThat(new ObjectMapper().readTree(requestBody.get()).at("/tools/0/function/parameters"))
                    .isEqualTo(new ObjectMapper().valueToTree(schema));
            assertThat(requestBody.get()).doesNotContain("\"strict\":true");
            ModelContinuation continuation = result.continuation().orElseThrow();
            ModelReply.UseTools reply = (ModelReply.UseTools) result.reply();
            SessionId sessionId = new SessionId("session-1");
            MessageJobId jobId = new MessageJobId("job-1");
            AssistantToolCallsMessage calls = new AssistantToolCallsMessage(sessionId, new SessionSequence(2),
                    Optional.of(jobId), Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, reply.message(), reply.requests());
            ToolObservation observation = new ToolObservation(sessionId, new SessionSequence(3), Optional.of(jobId),
                    Instant.EPOCH, MessageRole.TOOL, reply.requests().getFirst().toolCallId(), "lookup",
                    Map.of("isError", false, "result", Map.of("fee", 9)));

            ModelCallResult finalResult = models.defaultConversationModel().respond(
                    new ModelRequest(List.of(calls, observation), Map.of(calls.sequence(), continuation),
                            new ToolSnapshot(List.of(tool)), ModelRequest.Purpose.ANSWER), () -> 2, ignored -> { });

            assertThat(finalResult.reply()).isEqualTo(new ModelReply.Text("The fee is 9."));
            assertThat(providerRequests).hasValue(2);
            JsonNode wire = new ObjectMapper().readTree(requestBody.get());
            assertThat(wire.at("/messages/1/content").asText()).isEqualTo("Checking.");
            assertThat(wire.at("/messages/1/reasoning_content").asText()).isEqualTo("Need a lookup.");
            assertThat(wire.at("/messages/1/tool_calls/0/extra_content/google/thought_signature").asText()).isEqualTo("AQID");
            assertThat(wire.at("/messages/2/tool_call_id").asText()).isEqualTo("call-lookup");
            assertThat(wire.at("/messages/2/content").asText()).contains("\"fee\":9");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void projects_openai_tool_history_with_matching_ids_without_google_continuation_data() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, new AtomicReference<>(), requestBody,
                providerRequests, textResponse("After the tool.")));
        server.start();
        SessionId sessionId = new SessionId("session-1");
        MessageJobId jobId = new MessageJobId("job-1");
        ToolRequest request = new ToolRequest(new ToolCallId("call-history"), new ToolName("lookup"), Map.of("query", "fee"));
        AssistantToolCallsMessage calls = new AssistantToolCallsMessage(sessionId, new SessionSequence(2), Optional.of(jobId),
                Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, Optional.of("Earlier."), List.of(request));
        ToolObservation observation = new ToolObservation(sessionId, new SessionSequence(3), Optional.of(jobId), Instant.EPOCH,
                MessageRole.TOOL, request.toolCallId(), "lookup", Map.of("isError", false, "result", Map.of("fee", 9)));
        try (ConfiguredModels models = configuredModels(server)) {
            ModelCallResult result = models.defaultConversationModel().respond(new ModelRequest(List.of(calls, observation),
                    Map.of(), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), () -> 1, ignored -> { });

            assertThat(result.reply()).isEqualTo(new ModelReply.Text("After the tool."));
            assertThat(providerRequests).hasValue(1);
            assertThat(requestBody.get()).contains("\"id\":\"call-history\"", "\"tool_call_id\":\"call-history\"",
                    "\"name\":\"lookup\"", "Earlier.");
            assertThat(requestBody.get()).doesNotContain("opaque-google-continuation", "google-thought-signature");
        } finally {
            server.stop(0);
        }
    }

    private static ConfiguredModels configuredModels(HttpServer server) {
        return configuredModels(server, Duration.ofSeconds(2), null, null, new NoOpConversationTelemetry());
    }

    private static ConfiguredModels configuredModels(HttpServer server, Duration timeout) {
        return configuredModels(server, timeout, null, null, new NoOpConversationTelemetry());
    }

    private static ConfiguredModels configuredModels(HttpServer server, Duration timeout, Double temperature, Double topP) {
        return configuredModels(server, timeout, temperature, topP, new NoOpConversationTelemetry());
    }

    private static ConfiguredModels configuredModels(HttpServer server, ConversationTelemetry telemetry) {
        return configuredModels(server, Duration.ofSeconds(2), null, null, telemetry);
    }

    private static ConfiguredModels configuredModels(
            HttpServer server,
            Duration timeout,
            Double temperature,
            Double topP,
            ConversationTelemetry telemetry) {
        RuntimeProperties.ModelProfile profile = new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", "test-key",
                200_000, 1_234, temperature, topP, timeout, null,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "openai-primary",
                Map.of("openai-primary", profile)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        return new RuntimeConfiguration().configuredModels(new SystemPrompt(), telemetry,
                new ObjectMapper(), properties);
    }

    private static HttpServer errorServer(int status, String message, AtomicInteger providerRequests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            providerRequests.incrementAndGet();
            byte[] response = ("{\"error\":{\"message\":\"" + message + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }

    private static String textResponse(String text) {
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"gpt-5-mini",
                "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                """.formatted(text);
    }

    private static String openAiCacheUsageResponse(String cacheDetails) {
        return """
                {"id":"chatcmpl-cache","object":"chat.completion","created":0,"model":"gpt-5-mini",
                "choices":[{"index":0,"message":{"role":"assistant","content":"Cached response."},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10%s}}
                """.formatted(cacheDetails);
    }

    private static void respond(
            HttpExchange exchange,
            AtomicReference<String> requestPath,
            AtomicReference<String> requestBody,
            AtomicInteger calls,
            String body) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        calls.incrementAndGet();
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
