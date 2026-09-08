package com.java.system.sessionagent.bootstrap.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.conversation.port.out.ModelCallFailure;
import com.java.system.sessionagent.model.adapter.springai.SystemPrompt;
import com.java.system.sessionagent.model.adapter.springai.ConfiguredModels;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiConfiguredModelTest {
    @Test
    void maps_a_configured_openai_text_response_to_the_provider_neutral_port() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, requestPath, requestBody, calls, """
                {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"gpt-5-mini",
                "choices":[{"index":0,"message":{"role":"assistant","content":"Hello."},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}
                """));
        server.start();
        try (ConfiguredModels models = configuredModels(server)) {
            ModelCallResult result = models.defaultConversationModel().respond(
                    new ModelRequest(List.of(), new ToolSnapshot(List.of())), calls::incrementAndGet);

            assertThat(result.reply()).isEqualTo(new ModelReply.Text("Hello."));
            assertThat(calls).hasValue(1);
            assertThat(requestPath).hasValue("/v1/chat/completions");
            assertThat(requestBody.get()).contains("\"model\":\"gpt-5-mini\"", "\"store\":false",
                    "\"max_completion_tokens\":1234");
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

    private static ConfiguredModels configuredModels(HttpServer server) {
        RuntimeProperties.ModelProfile profile = new RuntimeProperties.ModelProfile("openai", "gpt-5-mini", "test-key",
                200_000, 1_234, null, null, Duration.ofSeconds(2), null,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        RuntimeProperties properties = new RuntimeProperties(new RuntimeProperties.Model(12, "openai-primary",
                Map.of("openai-primary", profile)), new RuntimeProperties.Worker(
                Duration.ofSeconds(1), Duration.ofSeconds(30), 3, Duration.ofSeconds(60)));
        return new RuntimeConfiguration().configuredModels(new SystemPrompt(), new NoOpConversationTelemetry(),
                new ObjectMapper(), properties);
    }

    private static void respond(
            HttpExchange exchange,
            AtomicReference<String> requestPath,
            AtomicReference<String> requestBody,
            AtomicInteger calls,
            String body) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
