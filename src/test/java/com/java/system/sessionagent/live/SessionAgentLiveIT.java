package com.java.system.sessionagent.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAgentLiveIT {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration POLL_DELAY = Duration.ofMillis(500);
    private static final Path REPORT_DIRECTORY = Path.of("target", "live-reports");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    @Test
    void completes_four_real_conversation_scenarios_through_http_and_worker() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SESSION_AGENT_LIVE")), "live opt-in is absent");
        String runtimeBaseUrl = requiredEnvironment("SESSION_AGENT_BASE_URL");
        String semanticBaseUrl = requiredEnvironment("SEMANTIC_BASE_URL");
        requiredEnvironment("GOOGLE_API_KEY");

        LiveRuntime runtime = new LiveRuntime(runtimeBaseUrl, semanticBaseUrl);
        List<ScenarioReport> reports = new ArrayList<>();
        try {
            reports.add(runPaymentMethods(runtime));
            reports.add(runRuntimeOnlyFee(runtime));
            reports.add(runAbsentBnpl(runtime));
            reports.add(runCancellationAndRefund(runtime));
        } finally {
            writeSafeReport(reports);
        }
    }

    private ScenarioReport runPaymentMethods(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("payment-methods", "目前有哪些支付方式？");
        String answer = state.assistantText();
        assertContainsOneOf(answer, "信用卡", "credit card");
        assertContainsOneOf(answer, "銀行轉帳", "bank transfer");
        assertContainsOneOf(answer, "錢包", "wallet");
        assertThat(state.sourceRepositoryIds()).contains("payment-service");
        return state.toReport("PAYMENT_METHODS_CONFIRMED");
    }

    private ScenarioReport runRuntimeOnlyFee(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("runtime-fee", "信用卡目前的手續費是多少？");
        String answer = state.assistantText();
        assertThat(answer).doesNotMatch("(?s).*\\b\\d+(?:\\.\\d+)?\\s*%.*");
        assertContainsOneOf(answer, "runtime", "執行", "設定", "json", "公式");
        assertThat(state.sourceRepositoryIds()).contains("payment-service");
        return state.toReport("RUNTIME_VALUE_NOT_INVENTED");
    }

    private ScenarioReport runAbsentBnpl(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("bnpl-absence", "目前是否支援先買後付？");
        assertContainsOneOf(state.assistantText(), "未發現", "沒有", "不支援", "not found", "not support", "not implemented");
        return state.toReport("ABSENT_BEHAVIOR_REPORTED");
    }

    private ScenarioReport runCancellationAndRefund(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("cancellation-refund", "取消訂單後，付款會自動退款嗎？");
        String answer = state.assistantText();
        assertContainsOneOf(answer, "取消", "cancel");
        assertContainsOneOf(answer, "未", "沒有", "無法", "not proven", "not found", "cannot confirm");
        assertThat(state.sourceRepositoryIds()).contains("order-service", "payment-service");
        return state.toReport("CANCELLATION_PROVEN_REFUND_UNPROVEN");
    }

    private void assertContainsOneOf(String answer, String... expectedIndicators) {
        String normalized = answer.toLowerCase();
        boolean found = false;
        for (String indicator : expectedIndicators) {
            if (normalized.contains(indicator.toLowerCase())) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(StringUtils.hasText(value), "required live environment is absent: " + name);
        return value;
    }

    private void writeSafeReport(List<ScenarioReport> reports) throws IOException {
        Files.createDirectories(REPORT_DIRECTORY);
        ObjectNode report = objectMapper.createObjectNode();
        report.put("model", System.getenv().getOrDefault("GOOGLE_GENAI_MODEL", "gemini-3.1-flash-lite"));
        report.put("springAiUsage", "unavailable from the local asynchronous HTTP contract");
        report.set("scenarios", objectMapper.valueToTree(reports));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(REPORT_DIRECTORY.resolve("session-agent-live-report.json").toFile(), report);
    }

    private final class LiveRuntime {

        private final String runtimeBaseUrl;
        private final String semanticBaseUrl;

        private LiveRuntime(String runtimeBaseUrl, String semanticBaseUrl) {
            this.runtimeBaseUrl = removeTrailingSlash(runtimeBaseUrl);
            this.semanticBaseUrl = removeTrailingSlash(semanticBaseUrl);
        }

        private ScenarioState ask(String scenario, String question) throws Exception {
            String sourceMessageId = UUID.randomUUID().toString();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("sessionKey", "live-" + scenario + "-" + UUID.randomUUID());
            request.put("participantId", "live-acceptance");
            request.put("sourceMessageId", sourceMessageId);
            request.put("message", question);
            JsonNode receipt = request("POST", runtimeBaseUrl + "/internal/messages", Optional.of(request));
            String sessionId = requiredText(receipt, "sessionId");
            String jobId = requiredText(receipt, "messageJobId");
            awaitDone(jobId);
            JsonNode messages = request("GET", runtimeBaseUrl + "/internal/sessions/" + sessionId + "/messages", Optional.empty());
            return inspectScenario(sessionId, jobId, messages);
        }

        private void awaitDone(String jobId) throws Exception {
            Instant deadline = Instant.now().plus(JOB_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                JsonNode job = request("GET", runtimeBaseUrl + "/internal/message-jobs/" + jobId, Optional.empty());
                if ("DONE".equals(requiredText(job, "status"))) {
                    return;
                }
                Thread.sleep(POLL_DELAY);
            }
            throw new AssertionError("message job did not reach DONE before the bounded timeout");
        }

        private ScenarioState inspectScenario(String sessionId, String jobId, JsonNode messages) throws Exception {
            assertThat(messages.isArray()).isTrue();
            List<JsonNode> toolMessages = new ArrayList<>();
            JsonNode assistant = objectMapper.createObjectNode();
            for (JsonNode message : messages) {
                if ("TOOL".equals(requiredText(message, "role"))) {
                    toolMessages.add(message);
                }
                if ("ASSISTANT".equals(requiredText(message, "role"))) {
                    assistant = message;
                }
            }
            assertThat(toolMessages).isNotEmpty();
            assertThat(requiredText(toolMessages.getFirst(), "toolName")).isEqualTo("list_repositories");
            assertThat(assistant.isObject()).isTrue();
            String assistantText = requiredText(assistant, "message");
            Set<String> catalogRepositoryIds = catalogRepositoryIds(toolMessages.getFirst());
            Map<String, JsonNode> resultsById = new HashMap<>();
            List<String> toolOrder = new ArrayList<>();
            List<RepositoryRevision> repositories = new ArrayList<>();
            Set<String> sourceRepositoryIds = new HashSet<>();
            for (JsonNode tool : toolMessages) {
                toolOrder.add(requiredText(tool, "toolName"));
                String resultId = requiredText(tool, "resultId");
                JsonNode result = request("GET", runtimeBaseUrl + "/internal/results/" + resultId, Optional.empty());
                assertThat(requiredText(result, "sessionId")).isEqualTo(sessionId);
                resultsById.put(resultId, result);
                if (tool.path("repositoryId").isTextual()) {
                    String repositoryId = tool.path("repositoryId").asText();
                    JsonNode arguments = parseStructuredJson(requiredText(result, "canonicalArguments"));
                    assertThat(requiredText(arguments, "repositoryId")).isEqualTo(repositoryId);
                    assertThat(catalogRepositoryIds).contains(repositoryId);
                    String revision = requiredText(tool, "revision");
                    assertThat(requiredText(result, "revision")).isEqualTo(revision);
                    assertThat(freshRevision(repositoryId)).isEqualTo(revision);
                    sourceRepositoryIds.add(repositoryId);
                    repositories.add(new RepositoryRevision(repositoryId, revision));
                }
            }
            List<String> citations = citationIds(assistant);
            assertThat(citations).isNotEmpty();
            for (String citation : citations) {
                JsonNode citedResult = resultsById.get(citation);
                assertThat(citedResult).isNotNull();
                assertThat(citedResult.path("citeable").asBoolean(false)).isTrue();
                String repositoryId = requiredText(citedResult, "repositoryId");
                assertThat(freshRevision(repositoryId)).isEqualTo(requiredText(citedResult, "revision"));
            }
            return new ScenarioState(sessionId, jobId, assistantText, toolOrder, repositories, citations, sourceRepositoryIds);
        }

        private Set<String> catalogRepositoryIds(JsonNode catalogTool) throws Exception {
            String catalogResultId = requiredText(catalogTool, "resultId");
            JsonNode result = request("GET", runtimeBaseUrl + "/internal/results/" + catalogResultId, Optional.empty());
            JsonNode catalog = parseStructuredJson(requiredText(result, "resultJson"));
            Set<String> repositoryIds = new HashSet<>();
            for (JsonNode repository : catalog.path("data").path("repositories")) {
                repositoryIds.add(requiredText(repository, "repositoryId"));
            }
            assertThat(repositoryIds).isNotEmpty();
            return repositoryIds;
        }

        private String freshRevision(String repositoryId) throws Exception {
            JsonNode repository = request("GET", semanticBaseUrl + "/v1/repositories/" + repositoryId, Optional.empty());
            return requiredText(repository, "currentRevision");
        }

        private JsonNode request(String method, String url, Optional<JsonNode> payload) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json");
            String token = System.getenv("SEMANTIC_API_TOKEN");
            if (url.startsWith(semanticBaseUrl) && StringUtils.hasText(token)) {
                builder.header("X-Api-Token", token);
            }
            if (payload.isPresent()) {
                JsonNode body = payload.orElseThrow();
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AssertionError("HTTP request returned non-success status: " + response.statusCode());
            }
            return parseStructuredJson(response.body());
        }
    }

    private JsonNode parseStructuredJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException exception) {
            throw new AssertionError("HTTP contract returned malformed JSON");
        }
    }

    private List<String> citationIds(JsonNode assistant) {
        List<String> citations = new ArrayList<>();
        for (JsonNode citation : assistant.path("citations")) {
            citations.add(citation.asText());
        }
        return citations;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        assertThat(value.isTextual()).isTrue();
        assertThat(StringUtils.hasText(value.asText())).isTrue();
        return value.asText();
    }

    private String removeTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record ScenarioState(
            String sessionId,
            String jobId,
            String assistantText,
            List<String> toolOrder,
            List<RepositoryRevision> repositories,
            List<String> citations,
            Set<String> sourceRepositoryIds) {

        private ScenarioReport toReport(String outcome) {
            return new ScenarioReport(sessionId, jobId, toolOrder, repositories, citations, outcome);
        }
    }

    private record RepositoryRevision(String repositoryId, String revision) {
    }

    private record ScenarioReport(
            String sessionId,
            String messageJobId,
            List<String> toolOrder,
            List<RepositoryRevision> repositories,
            List<String> citations,
            String outcome) {
    }
}
