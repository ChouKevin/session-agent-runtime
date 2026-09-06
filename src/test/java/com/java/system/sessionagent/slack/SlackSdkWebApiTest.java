package com.java.system.sessionagent.slack;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.RequestConfigurator;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
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

    private static SlackPostFailure postFailure(SlackApiException sdkFailure) throws Exception {
        Slack slack = Mockito.mock(Slack.class);
        MethodsClient methods = Mockito.mock(MethodsClient.class);
        Mockito.when(slack.methods("xoxb-test")).thenReturn(methods);
        Mockito.doThrow(sdkFailure).when(methods).chatPostMessage(
                Mockito.<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>any());
        Throwable thrown = catchThrowable(() -> new SlackSdkWebApi(properties(), slack).post(request()));
        assertThat(thrown).isInstanceOf(SlackPostFailure.class);
        return (SlackPostFailure) thrown;
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
