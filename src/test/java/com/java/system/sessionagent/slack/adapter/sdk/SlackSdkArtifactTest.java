package com.java.system.sessionagent.slack.adapter.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.slack.domain.SlackAnswerPresentation;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackPresentation;
import com.java.system.sessionagent.slack.domain.SlackUploadTicket;
import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.util.http.SlackHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SlackSdkArtifactTest {
    @Test
    void actual_sdk_preserves_markdown_and_allocates_uploads_and_finalizes_exact_utf8_in_original_thread() throws Exception {
        List<Captured> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server();
        server.createContext("/", exchange -> {
            calls.add(capture(exchange));
            String response = switch (exchange.getRequestURI().getPath()) {
                case "/files.getUploadURLExternal" -> "{\"ok\":true,\"file_id\":\"F42\",\"upload_url\":\"" + endpoint(server) + "upload/v1/capability\"}";
                case "/upload/v1/capability" -> "OK - 42";
                default -> "{\"ok\":true,\"ts\":\"2.000001\",\"files\":[{\"id\":\"F42\"}]}";
            };
            respond(exchange, 200, response);
        });
        server.start();
        String markdown = "# Report\n\n```java\nString x = \"中😀\";\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n";
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        try (SlackSdkWebApi api = localApi(server)) {
            api.post(new SlackPostRequest("C-original", "1.000001", markdown, SlackPresentation.MARKDOWN));
            api.post(new SlackPostRequest("C-original", "1.000001", "Queued *literally*"));
            SlackUploadTicket ticket = api.allocateUpload("answer.md", bytes.length);
            assertThat(ticket.fileId()).isEqualTo("F42");
            assertThat(ticket.toString()).doesNotContain("capability", endpoint(server));
            api.uploadBytes(ticket, bytes);
            api.finalizeUpload(ticket, "Complete response", "C-original", "1.000001");
        } finally {
            server.stop(0);
        }
        assertThat(calls).extracting(Captured::path).containsExactly("/chat.postMessage", "/chat.postMessage",
                "/files.getUploadURLExternal", "/upload/v1/capability", "/files.completeUploadExternal");
        Map<String, String> post = form(calls.get(0).body());
        assertThat(post).containsEntry("channel", "C-original").containsEntry("thread_ts", "1.000001")
                .containsEntry("text", SlackAnswerPresentation.INLINE_NOTIFICATION);
        JsonNode blocks = new ObjectMapper().readTree(post.get("blocks"));
        assertThat(blocks.size()).isOne();
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("markdown");
        assertThat(blocks.get(0).path("text").asText()).isEqualTo(markdown);
        Map<String, String> notice = form(calls.get(1).body());
        assertThat(notice).containsEntry("text", "Queued *literally*").doesNotContainKey("blocks");
        assertThat(notice.get("mrkdwn")).as("Slack boolean encoding disables Markdown").isIn("false", "0");
        assertThat(form(calls.get(2).body())).containsEntry("filename", "answer.md").containsEntry("length", Integer.toString(bytes.length));
        assertThat(calls.get(3).body()).isEqualTo(bytes);
        assertThat(calls.get(3).authorization()).isNull();
        Map<String, String> complete = form(calls.get(4).body());
        assertThat(complete).containsEntry("channel_id", "C-original").containsEntry("thread_ts", "1.000001");
        assertThat(new ObjectMapper().readTree(complete.get("files")).get(0).path("id").asText()).isEqualTo("F42");
    }

    @Test
    void raw_upload_never_follows_redirects_or_forwards_bot_credentials() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        HttpServer destination = server();
        destination.createContext("/", exchange -> { redirected.incrementAndGet(); respond(exchange, 200, "OK"); });
        destination.start();
        List<Captured> calls = new CopyOnWriteArrayList<>();
        HttpServer server = server();
        server.createContext("/", exchange -> {
            calls.add(capture(exchange));
            exchange.getResponseHeaders().add("Location", endpoint(destination) + "steal");
            respond(exchange, 307, "redirect");
        });
        server.start();
        try (SlackSdkWebApi api = localApi(server)) {
            Throwable failure = catchThrowable(() -> api.uploadBytes(new SlackUploadTicket("F1", endpoint(server) + "upload/v1/cap"), new byte[] {1}));
            assertThat(failure).isInstanceOfSatisfying(SlackPostFailure.class,
                    value -> assertThat(value.category()).isEqualTo(SlackDeliveryFailureCategory.PERMANENT));
        } finally { server.stop(0); destination.stop(0); }
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().authorization()).isNull();
        assertThat(redirected).hasValue(0);
    }

    @Test
    void production_upload_policy_rejects_http_foreign_hosts_userinfo_and_nonupload_paths_without_leaking_capabilities() throws Exception {
        HttpServer server = server();
        server.start();
        try (SlackSdkWebApi api = new SlackSdkWebApi(properties(), sdk(server))) {
            for (String url : List.of(endpoint(server) + "upload/v1/secret", "https://evil.example/upload/v1/secret",
                    "https://files.slack.com.evil.example/upload/v1/secret", "https://user@files.slack.com/upload/v1/secret",
                    "https://files.slack.com/not-upload/secret", "https://[invalid/secret")) {
                Throwable failure = catchThrowable(() -> api.uploadBytes(new SlackUploadTicket("F1", url), new byte[] {1}));
                assertThat(failure).isInstanceOfSatisfying(SlackPostFailure.class, value -> {
                    assertThat(value.category()).isEqualTo(SlackDeliveryFailureCategory.PERMANENT);
                    assertThat(value.getMessage()).isNull();
                    assertThat(value.getCause()).isNull();
                });
            }
        } finally { server.stop(0); }
    }

    @Test
    void actual_sdk_reports_permission_file_type_rate_limit_and_ambiguous_finalize_failures_without_transport_retry() throws Exception {
        for (FailureCase scenario : List.of(
                new FailureCase(200, "missing_scope", "", SlackDeliveryFailureCategory.PERMANENT),
                new FailureCase(200, "file_type_not_allowed", "", SlackDeliveryFailureCategory.PERMANENT),
                new FailureCase(429, "ratelimited", "7", SlackDeliveryFailureCategory.RATE_LIMIT),
                new FailureCase(200, "ratelimited", "7", SlackDeliveryFailureCategory.RATE_LIMIT),
                new FailureCase(503, "internal_error", "", SlackDeliveryFailureCategory.TRANSIENT),
                new FailureCase(200, "internal_error", "7", SlackDeliveryFailureCategory.TRANSIENT),
                new FailureCase(503, "internal_error", "7", SlackDeliveryFailureCategory.TRANSIENT),
                new FailureCase(429, "internal_error", "7", SlackDeliveryFailureCategory.TRANSIENT))) {
            List<String> paths = new CopyOnWriteArrayList<>();
            HttpServer server = server();
            server.createContext("/", exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                exchange.getRequestBody().readAllBytes();
                if (!scenario.retryAfter().isEmpty()) { exchange.getResponseHeaders().set("Retry-After", scenario.retryAfter()); }
                respond(exchange, scenario.status(), "{\"ok\":false,\"error\":\"" + scenario.error() + "\"}");
            });
            server.start();
            try (SlackSdkWebApi api = localApi(server)) {
                List<Runnable> operations = new ArrayList<>();
                operations.add(() -> api.allocateUpload("answer.md", 7));
                operations.add(() -> api.finalizeUpload(new SlackUploadTicket("F1", endpoint(server) + "upload/v1/cap"), "Answer", "C1", "1.0"));
                for (Runnable operation : operations) {
                    assertThat(catchThrowable(operation::run)).isInstanceOfSatisfying(SlackPostFailure.class, failure -> {
                        assertThat(failure.category()).isEqualTo(scenario.category());
                        assertThat(failure.getCause()).isNull();
                        assertThat(failure.retryAfter()).isEqualTo(scenario.category() == SlackDeliveryFailureCategory.RATE_LIMIT
                                ? java.util.Optional.of(Duration.ofSeconds(7)) : java.util.Optional.empty());
                    });
                }
            } finally { server.stop(0); }
            assertThat(paths).containsExactly("/files.getUploadURLExternal", "/files.completeUploadExternal");
        }
    }

    @Test
    void lost_or_mismatched_finalization_acknowledgement_is_ambiguous_and_is_not_transport_retried() throws Exception {
        for (String response : List.of("disconnect", "{\"ok\":true}", "{\"ok\":true,\"files\":[{\"id\":\"F-other\"}]}")) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = server();
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet(); // server accepted the one-time request
                if (response.equals("disconnect")) { exchange.close(); }
                else { respond(exchange, 200, response); }
            });
            server.start();
            try (SlackSdkWebApi api = localApi(server)) {
                assertThat(catchThrowable(() -> api.finalizeUpload(new SlackUploadTicket("F1", endpoint(server) + "upload/v1/cap"),
                        "Answer", "C1", "1.0"))).isInstanceOfSatisfying(SlackPostFailure.class,
                                failure -> assertThat(failure.category()).isEqualTo(SlackDeliveryFailureCategory.TRANSIENT));
            } finally { server.stop(0); }
            assertThat(requests).hasValue(1);
        }
    }

    @Test
    void expired_upload_capability_is_terminal_and_raw_rate_limits_expose_retry_after() throws Exception {
        for (int status : new int[] {403, 429, 503}) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = server();
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                if (status == 429) { exchange.getResponseHeaders().set("Retry-After", "7"); }
                respond(exchange, status, "not accepted");
            });
            server.start();
            try (SlackSdkWebApi api = localApi(server)) {
                assertThat(catchThrowable(() -> api.uploadBytes(new SlackUploadTicket("F1", endpoint(server) + "upload/v1/cap"),
                        new byte[] {1}))).isInstanceOfSatisfying(SlackPostFailure.class, failure -> {
                            assertThat(failure.category()).isEqualTo(status == 403 ? SlackDeliveryFailureCategory.PERMANENT
                                    : status == 429 ? SlackDeliveryFailureCategory.RATE_LIMIT : SlackDeliveryFailureCategory.TRANSIENT);
                            assertThat(failure.retryAfter()).isEqualTo(status == 429
                                    ? java.util.Optional.of(Duration.ofSeconds(7)) : java.util.Optional.empty());
                            assertThat(failure.getCause()).isNull();
                        });
            } finally { server.stop(0); }
            assertThat(requests).hasValue(1);
        }
    }

    @Test
    void unsupported_native_markdown_fails_without_a_plain_text_fallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{\"ok\":false,\"error\":\"invalid_blocks\"}");
        });
        server.start();
        try (SlackSdkWebApi api = localApi(server)) {
            assertThat(catchThrowable(() -> api.post(new SlackPostRequest("C1", "1.0", "# Native Markdown",
                    SlackPresentation.MARKDOWN)))).isInstanceOfSatisfying(SlackPostFailure.class,
                            failure -> assertThat(failure.category()).isEqualTo(SlackDeliveryFailureCategory.PERMANENT));
        } finally { server.stop(0); }
        assertThat(requests).hasValue(1);
    }

    private static SlackSdkWebApi localApi(HttpServer server) {
        URI origin = URI.create(endpoint(server));
        return new SlackSdkWebApi(properties(), sdk(server), uri -> origin.getScheme().equals(uri.getScheme())
                && origin.getHost().equals(uri.getHost()) && origin.getPort() == uri.getPort() && uri.getPath().startsWith("/upload/"));
    }
    private static Slack sdk(HttpServer server) {
        SlackConfig config = new SlackConfig();
        config.setStatsEnabled(false);
        config.setMethodsEndpointUrlPrefix(endpoint(server));
        config.setHttpClientCallTimeoutMillis(1_000);
        SlackHttpClient http = new SlackHttpClient(SlackHttpClient.buildOkHttpClient(config).newBuilder().retryOnConnectionFailure(false).build());
        http.setConfig(config);
        return Slack.getInstance(config, http);
    }
    private static SlackProperties properties() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
    private static HttpServer server() throws IOException { return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
    private static String endpoint(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    private static Captured capture(HttpExchange exchange) throws IOException {
        return new Captured(exchange.getRequestURI().getPath(), exchange.getRequestBody().readAllBytes(),
                exchange.getRequestHeaders().getFirst("Authorization"));
    }
    private static Map<String, String> form(byte[] bytes) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] pair = field.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private record Captured(String path, byte[] body, String authorization) {}
    private record FailureCase(int status, String error, String retryAfter, SlackDeliveryFailureCategory category) {}
}
