package com.java.system.sessionagent.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.util.Assert;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

final class FakeSemanticService implements AutoCloseable {

    private static final String PAYMENT_SERVICE = "payment-service";
    private static final String ORDER_SERVICE = "order-service";
    private static final String PAYMENT_REVISION = "payment-revision-1";
    private static final String ORDER_REVISION = "order-revision-1";
    private final HttpServer server;
    private final List<Call> calls = new ArrayList<>();

    FakeSemanticService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start fake Semantic Service", exception);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI requestUri = exchange.getRequestURI();
        String path = requestUri.getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(exchange.getRequestMethod(), path, requestUri.getQuery(), body));
        if (path.equals("/v1/repositories")) {
            respond(exchange, 200, repositories());
            return;
        }
        if (path.startsWith("/v1/repositories/")) {
            repository(exchange, path, requestUri);
            return;
        }
        if (path.equals("/v1/api-routes/lookup")) {
            if (!PAYMENT_SERVICE.equals(repositoryId(body)) || !hasExpectedRevision(body, PAYMENT_REVISION)) {
                respond(exchange, 404, "{}");
                return;
            }
            respond(exchange, 200, "{\"candidates\":[],\"observations\":[{\"code\":\"NOT_FOUND\",\"description\":\"No BNPL route was found\"}]}");
            return;
        }
        respond(exchange, 404, "{}");
    }

    private void repository(HttpExchange exchange, String path, URI requestUri) throws IOException {
        String suffix = path.substring("/v1/repositories/".length());
        String repositoryId = suffix.contains("/") ? suffix.substring(0, suffix.indexOf('/')) : suffix;
        if (!PAYMENT_SERVICE.equals(repositoryId) && !ORDER_SERVICE.equals(repositoryId)) {
            respond(exchange, 404, "{}");
            return;
        }
        if (suffix.endsWith("/entry-points")) {
            if (!hasExpectedRevision(requestUri, revision(repositoryId))) {
                respond(exchange, 409, "{}");
                return;
            }
            respond(exchange, 200, entryPoints(repositoryId));
            return;
        }
        respond(exchange, 200, repository(repositoryId));
    }

    private static String repositories() {
        return """
                [{"repoId":"payment-service","mode":"REMOTE","displayName":"Payment Service","currentBranch":"main","currentRevision":"payment-revision-1","cloned":true},
                 {"repoId":"order-service","mode":"REMOTE","displayName":"Order Service","currentBranch":"main","currentRevision":"order-revision-1","cloned":true}]
                """;
    }

    private static String repository(String repositoryId) {
        String displayName = PAYMENT_SERVICE.equals(repositoryId) ? "Payment Service" : "Order Service";
        String revision = revision(repositoryId);
        return "{\"repoId\":\"%s\",\"mode\":\"REMOTE\",\"displayName\":\"%s\",\"currentBranch\":\"main\",\"currentRevision\":\"%s\",\"cloned\":true}"
                .formatted(repositoryId, displayName, revision);
    }

    private static String entryPoints(String repositoryId) {
        String revision = revision(repositoryId);
        String description = PAYMENT_SERVICE.equals(repositoryId)
                ? "Payment methods include credit card, bank transfer, and wallet; fee formula is loaded from JSON settings."
                : "Order cancellation is implemented before payment refund handling.";
        return """
                {"repoId":"%s","analyzedRevision":"%s","entryPoints":[{"className":"ConversationFixture","packageName":"com.example","packagePath":"com/example","description":"%s","basePaths":[],"methods":[]}]}
                """.formatted(repositoryId, revision, description);
    }

    private static String repositoryId(String requestBody) {
        int key = requestBody.indexOf("\"repoId\":\"");
        Assert.isTrue(key >= 0, "Typed request must include repository ID");
        int start = key + "\"repoId\":\"".length();
        int end = requestBody.indexOf('"', start);
        Assert.isTrue(end > start, "Typed request repository ID must be populated");
        return requestBody.substring(start, end);
    }

    private static String revision(String repositoryId) {
        return PAYMENT_SERVICE.equals(repositoryId) ? PAYMENT_REVISION : ORDER_REVISION;
    }

    private static boolean hasExpectedRevision(String requestBody, String revision) {
        return requestBody.contains("\"expectedRevision\":\"" + revision + "\"");
    }

    private static boolean hasExpectedRevision(URI requestUri, String revision) {
        return Optional.ofNullable(requestUri.getQuery()).stream()
                .flatMap(query -> Arrays.stream(query.split("&")))
                .anyMatch(parameter -> parameter.equals("expectedRevision=" + revision));
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    record Call(String method, String path, String query, String body) {
    }
}
