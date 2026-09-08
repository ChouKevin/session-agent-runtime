package com.java.system.sessionagent.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolCallId;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.ToolRequest;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.tool.domain.ToolName;
import com.java.system.sessionagent.tool.port.ToolBinding;
import com.java.system.sessionagent.tool.port.ToolDefinition;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiConfiguredModelTest {
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
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), reservations::incrementAndGet);

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
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1))
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
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TRANSIENT));
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
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1))
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
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1))
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
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), () -> 1))
                    .isInstanceOfSatisfying(ModelCallFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ModelCallFailure.Kind.TRANSIENT));
            assertThat(providerRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void maps_openai_tool_calls_with_assistant_text_and_preserves_opaque_tool_schema() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger providerRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, new AtomicReference<>(), requestBody,
                providerRequests, """
                {"id":"chatcmpl-2","object":"chat.completion","created":0,"model":"gpt-5-mini",
                "choices":[{"index":0,"message":{"role":"assistant","content":"Checking.","tool_calls":[{"id":"call-lookup","type":"function","function":{"name":"lookup","arguments":"{\\"query\\":\\"fee\\"}"}}]},"finish_reason":"tool_calls"}],
                "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                """));
        server.start();
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query"), "additionalProperties", false);
        ToolBinding tool = new ToolBinding(ToolDefinition.fromExposedName("lookup", "Look up a fee", schema),
                arguments -> new com.java.system.sessionagent.tool.port.ToolOutput(false, Map.of()));
        try (ConfiguredModels models = configuredModels(server)) {
            ModelCallResult result = models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of(tool))), () -> 1);

            assertThat(result.reply()).isEqualTo(new ModelReply.UseTools(Optional.of("Checking."), List.of(
                    new ToolRequest(new ToolCallId("call-lookup"), new ToolName("lookup"), Map.of("query", "fee")))));
            assertThat(providerRequests).hasValue(1);
            assertThat(new ObjectMapper().readTree(requestBody.get()).at("/tools/0/function/parameters"))
                    .isEqualTo(new ObjectMapper().valueToTree(schema));
            assertThat(requestBody.get()).doesNotContain("\"strict\":true");
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
                    Map.of(calls.sequence(), new ModelContinuation(new ModelRouteId("google-primary"), "google-thought-signature",
                            "opaque-google-continuation".getBytes(StandardCharsets.UTF_8))), new ToolSnapshot(List.of())), () -> 1);

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
        return configuredModels(server, Duration.ofSeconds(2), null, null);
    }

    private static ConfiguredModels configuredModels(HttpServer server, Duration timeout) {
        return configuredModels(server, timeout, null, null);
    }

    private static ConfiguredModels configuredModels(HttpServer server, Duration timeout, Double temperature, Double topP) {
        RuntimeProperties.ModelProfile profile = new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", "test-key",
                200_000, 1_234, temperature, topP, timeout, null,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "openai-primary",
                Map.of("openai-primary", profile)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        return new RuntimeConfiguration().configuredModels(new SystemPrompt(), new NoOpConversationTelemetry(),
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
