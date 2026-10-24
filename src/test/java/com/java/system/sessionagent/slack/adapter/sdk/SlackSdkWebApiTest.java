package com.java.system.sessionagent.slack.adapter.sdk;

import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.slack.domain.SlackDeliveryFailureCategory;
import com.java.system.sessionagent.slack.domain.SlackPostFailure;
import com.java.system.sessionagent.slack.domain.SlackPostRequest;
import com.java.system.sessionagent.slack.domain.SlackUpdateRequest;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.RequestConfigurator;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.model.ErrorResponseMetadata;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SlackSdkWebApiTest {

    @Test
    void resolves_the_specific_threaded_message_with_real_sdk_and_rejects_wrong_identity_or_unsafe_links() throws Exception {
        String link = "https://example.slack.com/archives/C1/p2000002?thread_ts=1.000001&cid=C1";
        for (String returned : List.of(link, "https://example.slack.com/archives/C1/p1000001",
                "https://example.slack.com/archives/C2/p2000002", "https://evil.example/archives/C1/p2000002", "")) {
            List<String> requests = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI().toString() + new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                byte[] response = ("{\"ok\":true,\"permalink\":\"" + returned + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream body = exchange.getResponseBody()) { body.write(response); }
            });
            server.start();
            SlackConfig config = new SlackConfig();
            config.setStatsEnabled(false);
            config.setMethodsEndpointUrlPrefix(methodsEndpoint(server));
            try (SlackSdkWebApi api = new SlackSdkWebApi(properties(), Slack.getInstance(config))) {
                if (returned.equals(link)) {
                    assertThat(api.getPermalink("C1", "2.000002")).isEqualTo(link);
                } else {
                    assertThat(catchThrowable(() -> api.getPermalink("C1", "2.000002")))
                            .isInstanceOfSatisfying(SlackPostFailure.class,
                                    failure -> assertThat(failure.category()).isEqualTo(SlackDeliveryFailureCategory.PERMANENT));
                }
            } finally {
                server.stop(0);
            }
            assertThat(requests).singleElement().satisfies(request -> assertThat(java.net.URLDecoder.decode(request,
                    java.nio.charset.StandardCharsets.UTF_8)).contains("/chat.getPermalink", "channel=C1", "message_ts=2.000002"));
        }
    }

    @Test
    void updates_the_confirmed_notice_with_the_real_sdk_without_posting_a_replacement() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"ok\":true,\"channel\":\"C2\",\"ts\":\"9.000002\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        SlackConfig config = new SlackConfig();
        config.setStatsEnabled(false);
        config.setMethodsEndpointUrlPrefix(methodsEndpoint(server));
        try (SlackSdkWebApi api = new SlackSdkWebApi(properties(), Slack.getInstance(config))) {
            api.update(new SlackUpdateRequest("C2", "9.000002", "Last confirmed UTC"));
        } finally {
            server.stop(0);
        }
        assertThat(paths).containsExactly("/chat.update");
        assertThat(authorization).containsExactly("Bearer xoxb-test");
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(java.net.URLDecoder.decode(body, java.nio.charset.StandardCharsets.UTF_8))
                    .contains("channel=C2", "ts=9.000002", "text=Last confirmed UTC");
            assertThat(body).doesNotContain("thread_ts");
        });
    }

    @Test
    void real_sdk_progress_calls_check_ok_and_classify_deleted_permission_rate_limit_and_ambiguous_failures() throws Exception {
        for (UpdateCase scenario : List.of(
                new UpdateCase(200, "{\"ok\":false,\"error\":\"message_not_found\"}", "", SlackDeliveryFailureCategory.PERMANENT),
                new UpdateCase(200, "{\"ok\":false,\"error\":\"cant_update_message\"}", "", SlackDeliveryFailureCategory.PERMANENT),
                new UpdateCase(200, "{\"ok\":false,\"error\":\"missing_scope\"}", "", SlackDeliveryFailureCategory.PERMANENT),
                new UpdateCase(403, "{\"ok\":false,\"error\":\"no_permission\"}", "", SlackDeliveryFailureCategory.PERMANENT),
                new UpdateCase(429, "{\"ok\":false,\"error\":\"ratelimited\"}", "7", SlackDeliveryFailureCategory.RATE_LIMIT),
                new UpdateCase(200, "{\"ok\":false,\"error\":\"ratelimited\"}", "7", SlackDeliveryFailureCategory.RATE_LIMIT),
                new UpdateCase(503, "{\"ok\":false,\"error\":\"internal_error\"}", "", SlackDeliveryFailureCategory.TRANSIENT))) {
            List<String> paths = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                if (!scenario.retryAfter().isEmpty()) { exchange.getResponseHeaders().set("Retry-After", scenario.retryAfter()); }
                byte[] response = scenario.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(scenario.status(), response.length);
                try (OutputStream body = exchange.getResponseBody()) { body.write(response); }
            });
            server.start();
            SlackConfig config = new SlackConfig();
            config.setStatsEnabled(false);
            config.setMethodsEndpointUrlPrefix(methodsEndpoint(server));
            try (SlackSdkWebApi api = new SlackSdkWebApi(properties(), Slack.getInstance(config))) {
                Throwable thrown = catchThrowable(() -> api.update(new SlackUpdateRequest("C2", "9.000002", "Safe status")));
                assertThat(thrown).as(scenario.toString()).isInstanceOf(SlackPostFailure.class);
                SlackPostFailure failure = (SlackPostFailure) thrown;
                assertThat(failure.category()).isEqualTo(scenario.category());
                assertThat(failure.getMessage()).isNull();
                assertThat(failure.getCause()).isNull();
                assertThat(failure.retryAfter()).isEqualTo(scenario.retryAfter().isEmpty()
                        ? java.util.Optional.empty() : java.util.Optional.of(Duration.ofSeconds(7)));
                Throwable lookupThrown = catchThrowable(() -> api.getPermalink("C2", "9.000002"));
                assertThat(lookupThrown).as(scenario.toString()).isInstanceOfSatisfying(SlackPostFailure.class, lookupFailure -> {
                    assertThat(lookupFailure.category()).isEqualTo(scenario.category());
                    assertThat(lookupFailure.retryAfter()).isEqualTo(failure.retryAfter());
                    assertThat(lookupFailure.getMessage()).isNull();
                    assertThat(lookupFailure.getCause()).isNull();
                });
            } finally {
                server.stop(0);
            }
            assertThat(paths).containsExactly("/chat.update", "/chat.getPermalink");
        }
    }

    private record UpdateCase(int status, String body, String retryAfter, SlackDeliveryFailureCategory category) { }

    @Test
    void classifies_an_http_429_sdk_exception_using_its_retry_after_header() throws Exception {
        SlackApiException rateLimited = slackApiException(429, "rate_limited", Map.of("Retry-After", "7"));

        SlackPostFailure failure = postFailure(rateLimited);

        assertThat(failure.category()).isEqualTo(SlackDeliveryFailureCategory.RATE_LIMIT);
        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(7));
    }

    @Test
    void classifies_documented_deterministic_post_message_sdk_errors_as_permanent() throws Exception {
        for (String error : List.of("missing_scope", "no_permission", "msg_too_long", "invalid_arguments", "channel_not_found")) {
            SlackPostFailure failure = postFailure(slackApiException(400, error, Map.of()));

            assertThat(failure.category()).as(error).isEqualTo(SlackDeliveryFailureCategory.PERMANENT);
            assertThat(failure.retryAfter()).as(error).isEmpty();
        }
    }

    @Test
    void cold_delivery_client_posts_once_without_auth_test_and_uses_the_bounded_timeout() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> respondToSlackRequest(exchange, paths));
        server.start();

        AtomicReference<SlackConfig> configuredClient = new AtomicReference<>();
        try (MockedStatic<Slack> staticSlack = Mockito.mockStatic(Slack.class, Mockito.CALLS_REAL_METHODS)) {
            staticSlack.when(() -> Slack.getInstance(Mockito.any(SlackConfig.class))).thenAnswer(invocation -> {
                SlackConfig config = invocation.getArgument(0);
                config.setMethodsEndpointUrlPrefix(methodsEndpoint(server));
                configuredClient.set(config);
                return invocation.callRealMethod();
            });
            try (SlackSdkWebApi webApi = new SlackSdkWebApi(properties())) {
                assertThat(webApi.post(request())).isEqualTo("2.000002");
            }
        } finally {
            server.stop(0);
        }

        SlackConfig config = configuredClient.get();
        assertThat(config).isNotNull();
        assertThat(config.isStatsEnabled()).isFalse();
        assertThat(config.getHttpClientCallTimeoutMillis()).isEqualTo(25_000);
        assertThat(paths).containsExactly("/chat.postMessage");
    }

    @Test
    void retains_the_timestamp_and_marks_a_truncated_ok_response_as_permanent() throws Exception {
        Slack slack = Mockito.mock(Slack.class);
        MethodsClient methods = Mockito.mock(MethodsClient.class);
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        ErrorResponseMetadata metadata = new ErrorResponseMetadata();
        metadata.setWarnings(List.of("message_truncated"));
        response.setOk(true);
        response.setTs("2.000003");
        response.setResponseMetadata(metadata);
        Mockito.when(slack.methods("xoxb-test")).thenReturn(methods);
        Mockito.when(methods.chatPostMessage(Mockito.<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>any()))
                .thenReturn(response);

        try (SlackSdkWebApi webApi = new SlackSdkWebApi(properties(), slack)) {
            Throwable thrown = catchThrowable(() -> webApi.post(request()));

            assertThat(thrown).isInstanceOf(SlackPostFailure.class);
            SlackPostFailure failure = (SlackPostFailure) thrown;
            assertThat(failure.category()).isEqualTo(SlackDeliveryFailureCategory.PERMANENT);
            assertThat(failure.slackMessageTs()).contains("2.000003");
        }
    }

    @Test
    void accepts_an_ok_response_with_an_unrelated_warning() throws Exception {
        Slack slack = Mockito.mock(Slack.class);
        MethodsClient methods = Mockito.mock(MethodsClient.class);
        ChatPostMessageResponse response = new ChatPostMessageResponse();
        ErrorResponseMetadata metadata = new ErrorResponseMetadata();
        metadata.setWarnings(List.of("other_warning"));
        response.setOk(true);
        response.setTs("2.000004");
        response.setResponseMetadata(metadata);
        Mockito.when(slack.methods("xoxb-test")).thenReturn(methods);
        Mockito.when(methods.chatPostMessage(Mockito.<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>any()))
                .thenReturn(response);

        try (SlackSdkWebApi webApi = new SlackSdkWebApi(properties(), slack)) {
            assertThat(webApi.post(request())).isEqualTo("2.000004");
        }
    }

    private static SlackPostFailure postFailure(SlackApiException sdkFailure) throws Exception {
        Slack slack = Mockito.mock(Slack.class);
        MethodsClient methods = Mockito.mock(MethodsClient.class);
        Mockito.when(slack.methods("xoxb-test")).thenReturn(methods);
        Mockito.doThrow(sdkFailure).when(methods).chatPostMessage(
                Mockito.<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>any());
        try (SlackSdkWebApi webApi = new SlackSdkWebApi(properties(), slack)) {
            Throwable thrown = catchThrowable(() -> webApi.post(request()));
            assertThat(thrown).isInstanceOf(SlackPostFailure.class);
            return (SlackPostFailure) thrown;
        }
    }

    private static SlackApiException slackApiException(int status, String error, Map<String, String> headers) {
        Request request = new Request.Builder().url("https://slack.test/api/chat.postMessage").build();
        Response.Builder response = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test");
        headers.forEach(response::header);
        return new SlackApiException(response.build(), "{\"ok\":false,\"error\":\"%s\"}".formatted(error));
    }

    private static SlackProperties properties() {
        return new SlackProperties("xapp-test", "xoxb-test", "UBOT", Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static SlackPostRequest request() {
        return new SlackPostRequest("C1", "1.000001", "Committed terminal response");
    }

    private static String methodsEndpoint(HttpServer server) {
        return "http://127.0.0.1:%d/".formatted(server.getAddress().getPort());
    }

    private static void respondToSlackRequest(
            HttpExchange exchange,
            List<String> paths) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        byte[] response = "{\"ok\":true,\"ts\":\"2.000002\"}".getBytes();
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(response);
        }
    }
}
