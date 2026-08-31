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
            String repositoryId = repositoryId(body);
            if (!PAYMENT_SERVICE.equals(repositoryId) || !hasRevision(body, PAYMENT_REVISION)) {
                respond(exchange, 404, repositoryNotFound());
                return;
            }
            respond(exchange, 200, envelope(repositoryId, PAYMENT_REVISION, "{\"candidates\":[],\"observations\":[{\"code\":\"NOT_FOUND\",\"description\":\"No BNPL route was found\"}]}"));
            return;
        }
        if (path.equals("/v1/code-facts/search")) {
            String repositoryId = repositoryId(body);
            if (!PAYMENT_SERVICE.equals(repositoryId) || !hasRevision(body, PAYMENT_REVISION)) {
                respond(exchange, 404, repositoryNotFound());
                return;
            }
            respond(exchange, 200, envelope(repositoryId, PAYMENT_REVISION,
                    "{\"totalCount\":0,\"hasMore\":false,\"coverage\":{\"issues\":[]},\"facts\":[]}"));
            return;
        }
        respond(exchange, 404, "{}");
    }

    private void repository(HttpExchange exchange, String path, URI requestUri) throws IOException {
        String suffix = path.substring("/v1/repositories/".length());
        String repositoryId = suffix.contains("/") ? suffix.substring(0, suffix.indexOf('/')) : suffix;
        if (!PAYMENT_SERVICE.equals(repositoryId) && !ORDER_SERVICE.equals(repositoryId)) {
            respond(exchange, 404, repositoryNotFound());
            return;
        }
        if (suffix.endsWith("/entry-points")) {
            if (!hasRevision(requestUri, revision(repositoryId))) {
                respond(exchange, 409, revisionOutdated(repositoryId, requestedRevision(requestUri)));
                return;
            }
            respond(exchange, 200, envelope(repositoryId, revision(repositoryId), entryPoints(repositoryId)));
            return;
        }
        respond(exchange, 200, repository(repositoryId));
    }

    private static String repositories() {
        return """
                [{"repositoryId":{"value":"payment-service"},"revision":{"value":"payment-revision-1"},"generationId":{"value":"g-payment"},"manifestDigest":{"value":"d-payment"},"publishedAt":"2026-08-25T00:00:00Z"},
                 {"repositoryId":{"value":"order-service"},"revision":{"value":"order-revision-1"},"generationId":{"value":"g-order"},"manifestDigest":{"value":"d-order"},"publishedAt":"2026-08-25T00:00:00Z"}]
                """;
    }

    private static String repository(String repositoryId) {
        String revision = revision(repositoryId);
        return "{\"repositoryId\":{\"value\":\"%s\"},\"revision\":{\"value\":\"%s\"},\"generationId\":{\"value\":\"g\"},\"manifestDigest\":{\"value\":\"d\"},\"publishedAt\":\"2026-08-25T00:00:00Z\"}".formatted(repositoryId, revision);
    }

    private static String repositoryNotFound() {
        return """
                {"code":"REPOSITORY_NOT_FOUND","message":"repository is not configured"}
                """;
    }

    private static String revisionOutdated(String repositoryId, String requestedRevision) {
        return "{\"code\":\"REVISION_OUTDATED\",\"repositoryId\":\"%s\",\"requestedRevision\":\"%s\",\"currentRevision\":\"%s\",\"retryGuidance\":\"Retry with currentRevision.\"}"
                .formatted(repositoryId, requestedRevision, revision(repositoryId));
    }

    private static String entryPoints(String repositoryId) {
        String revision = revision(repositoryId);
        String description = PAYMENT_SERVICE.equals(repositoryId)
                ? "Payment methods include credit card, bank transfer, and wallet; fee formula is loaded from JSON settings."
                : "Order cancellation is implemented before payment refund handling.";
        return """
                {"entryPoints":[{
                  "sourceType":{
                    "javaType":{"packageName":"com.example","className":"ConversationFixture"},
                    "sourceFile":"src/main/java/com/example/ConversationFixture.java"
                  },
                  "description":"%s","basePaths":[],"methods":[]
                }]}
                """.formatted(description);
    }

    private static String repositoryId(String requestBody) {
        int key = requestBody.indexOf("\"repositoryId\":\"");
        Assert.isTrue(key >= 0, "Typed request must include repository ID");
        int start = key + "\"repositoryId\":\"".length();
        int end = requestBody.indexOf('"', start);
        Assert.isTrue(end > start, "Typed request repository ID must be populated");
        return requestBody.substring(start, end);
    }

    private static String revision(String repositoryId) {
        return PAYMENT_SERVICE.equals(repositoryId) ? PAYMENT_REVISION : ORDER_REVISION;
    }

    private static String envelope(String repositoryId, String revision, String result) {
        return "{\"repositoryId\":\"%s\",\"revision\":\"%s\",\"result\":%s}".formatted(repositoryId, revision, result);
    }

    private static boolean hasRevision(String requestBody, String revision) {
        return requestBody.contains("\"revision\":\"" + revision + "\"");
    }

    private static boolean hasRevision(URI requestUri, String revision) {
        return Optional.ofNullable(requestUri.getQuery()).stream()
                .flatMap(query -> Arrays.stream(query.split("&")))
                .anyMatch(parameter -> parameter.equals("revision=" + revision));
    }

    private static String requestedRevision(URI requestUri) {
        return Optional.ofNullable(requestUri.getQuery()).stream()
                .flatMap(query -> Arrays.stream(query.split("&")))
                .filter(parameter -> parameter.startsWith("revision="))
                .map(parameter -> parameter.substring("revision=".length()))
                .findFirst().orElse("missing-revision");
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
