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
    void completes_five_real_conversation_scenarios_through_http_and_worker() throws Exception {
        LiveRuntime runtime = liveRuntime();
        List<ScenarioReport> reports = new ArrayList<>();
        try {
            reports.add(runPaymentMethods(runtime));
            reports.add(runRuntimeOnlyFee(runtime));
            reports.add(runVideoFormats(runtime));
            reports.add(runAbsentBnpl(runtime));
            reports.add(runCancellationAndRefund(runtime));
        } finally {
            writeSafeReport(reports);
        }
    }

    @Test
    void completes_cancellation_and_refund_scenario_through_http_and_worker() throws Exception {
        LiveRuntime runtime = liveRuntime();
        List<ScenarioReport> reports = new ArrayList<>();
        try {
            reports.add(runCancellationAndRefund(runtime));
        } finally {
            writeSafeReport(reports);
        }
    }

    @Test
    void records_repository_catalog_at_r1() throws Exception {
        String sessionKey = requiredHistorySessionKey();
        LiveRuntime runtime = liveRuntime();
        ScenarioState state = runtime.ask(sessionKey, "影片上傳支援哪些格式？");

        assertContainsOneOf(state.assistantText(), "MP4", "mp4");
        assertContainsOneOf(state.assistantText(), "WEBM", "webm");
        assertContainsOneOf(state.assistantText(), "MOV", "mov");
        assertThat(state.sourceRepositoryIds()).contains("video-service");
        assertThat(state.citedRepositoryIds()).contains("video-service");
        assertThat(catalogRevision(currentCatalog(state), "payment-service")).isNotBlank();
    }

    @Test
    void recovers_payment_query_at_r2() throws Exception {
        String sessionKey = requiredHistorySessionKey();
        LiveRuntime runtime = liveRuntime();
        ScenarioState state = runtime.ask(sessionKey, "目前有哪些支付方式？");
        JsonNode outdatedFeedback = revisionOutdatedFeedback(state);
        long feedbackSequence = requiredSequence(outdatedFeedback);
        JsonNode payload = parseStructuredJson(requiredText(outdatedFeedback, "message"));
        JsonNode rejectedArguments = parseStructuredJson(requiredText(outdatedFeedback, "rejectedArguments"));
        String failedToolName = requiredText(outdatedFeedback, "toolName");
        String rejectedRepositoryId = requiredText(rejectedArguments, "repositoryId");
        String requestedRevision = catalogRevision(state.previousCatalog().orElseThrow(
                () -> new AssertionError("session did not retain the earlier repository catalog")), "payment-service");
        ToolResult retry = state.toolResults().stream()
                .filter(tool -> tool.toolName().equals(failedToolName))
                .filter(tool -> tool.repositoryId().filter(rejectedRepositoryId::equals).isPresent())
                .filter(tool -> tool.revision().isPresent())
                .filter(tool -> tool.messageSequence() > feedbackSequence)
                .findFirst()
                .orElseThrow(() -> new AssertionError("session did not retry the useful tool"));
        String currentRevision = retry.revision().orElseThrow();
        JsonNode retriedArguments = parseStructuredJson(retry.canonicalArguments());

        assertThat(requiredText(payload, "repositoryId")).isEqualTo("payment-service");
        assertThat(requestedRevision).isNotBlank();
        assertThat(currentRevision).isNotBlank().isNotEqualTo(requestedRevision);
        assertThat(requiredText(payload, "requestedRevision")).isEqualTo(requestedRevision);
        assertThat(requiredText(payload, "currentRevision")).isEqualTo(currentRevision);
        assertThat(rejectedRepositoryId).isEqualTo("payment-service");
        assertThat(requiredText(rejectedArguments, "revision")).isEqualTo(requestedRevision);
        assertThat(requiredText(retriedArguments, "revision")).isEqualTo(currentRevision);
        assertThat(state.assistantText()).contains("MOBILE_PAYMENT");
        assertThat(state.citations()).isNotEmpty();
        assertThat(state.citations()).allSatisfy(citation ->
                assertThat(state.resultById(citation).revision()).contains(currentRevision));
    }

    @Test
    void retains_only_nonblank_external_history_keys() {
        assertThat(externalHistorySessionKey("history-key")).contains("history-key");
        assertThat(externalHistorySessionKey("  history-key  ")).contains("  history-key  ");
        assertThat(externalHistorySessionKey(" ")).isEmpty();
        assertThat(externalHistorySessionKey(null)).isEmpty();
    }

    private ScenarioReport runPaymentMethods(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("live-payment-methods-" + UUID.randomUUID(), "目前有哪些支付方式？");
        String answer = state.assistantText();
        assertContainsOneOf(answer, "信用卡", "credit card", "CREDIT_CARD");
        assertContainsOneOf(answer, "銀行轉帳", "银行转账", "bank transfer", "BANK_TRANSFER");
        assertContainsOneOf(answer, "錢包", "钱包", "wallet", "WALLET");
        assertThat(state.sourceRepositoryIds()).contains("payment-service");
        return state.toReport("PAYMENT_METHODS_CONFIRMED");
    }

    private ScenarioReport runRuntimeOnlyFee(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("live-runtime-fee-" + UUID.randomUUID(), "信用卡目前的手續費是多少？");
        String answer = state.assistantText();
        assertThat(answer).doesNotMatch("(?s).*\\b\\d+(?:\\.\\d+)?\\s*%.*");
        assertContainsOneOf(answer, "runtime", "執行", "設定", "json", "公式");
        assertThat(state.sourceRepositoryIds()).contains("payment-service");
        return state.toReport("RUNTIME_VALUE_NOT_INVENTED");
    }

    private ScenarioReport runVideoFormats(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("live-video-formats-" + UUID.randomUUID(), "影片上傳支援哪些格式？");
        String answer = state.assistantText();
        assertContainsOneOf(answer, "MP4", "mp4");
        assertContainsOneOf(answer, "WEBM", "webm");
        assertContainsOneOf(answer, "MOV", "mov");
        assertThat(state.sourceRepositoryIds()).contains("video-service");
        assertThat(state.citedRepositoryIds()).contains("video-service");
        return state.toReport("VIDEO_FORMATS_CONFIRMED");
    }

    private ScenarioReport runAbsentBnpl(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("live-bnpl-absence-" + UUID.randomUUID(), "目前是否支援先買後付？");
        assertContainsOneOf(state.assistantText(), "未發現", "未包含", "沒有", "未支援", "不支援", "未實作",
                "not found", "not support", "not implemented");
        List<String> fullCoverageEmptySearchIds = state.toolResults().stream()
                .filter(tool -> tool.toolName().equals("codebase_search_code_facts"))
                .filter(tool -> hasFullCoverageEmptyResult(tool.resultJson()))
                .map(ToolResult::resultId)
                .toList();
        assertThat(fullCoverageEmptySearchIds)
                .withFailMessage("absence answer did not use a full-coverage empty code search")
                .isNotEmpty();
        assertThat(state.citations()).anyMatch(fullCoverageEmptySearchIds::contains);
        return state.toReport("ABSENT_BEHAVIOR_REPORTED");
    }

    private ScenarioReport runCancellationAndRefund(LiveRuntime runtime) throws Exception {
        ScenarioState state = runtime.ask("live-cancellation-refund-" + UUID.randomUUID(), "取消訂單後，付款會自動退款嗎？");
        String answer = state.assistantText();
        assertContainsOneOf(answer, "取消", "cancel");
        assertContainsOneOf(answer, "無法", "未能", "不能", "進一步確認", "尚待確認",
                "not proven", "cannot", "could not", "further confirmation");
        assertContainsOneOf(answer, "程式碼", "codebase", "code");
        assertContainsOneOf(answer, "執行", "runtime", "外部", "external");
        assertThat(answer).doesNotContain("不會自動退款", "不會退款");
        assertThat(answer.toLowerCase()).doesNotContain("will not automatically refund", "does not automatically refund");
        assertThat(state.sourceRepositoryIds()).contains("order-service", "payment-service");
        assertThat(state.citedRepositoryIds()).contains("order-service", "payment-service");
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
        assertThat(found)
                .withFailMessage("answer did not contain any expected indicator %s: %s", List.of(expectedIndicators), answer)
                .isTrue();
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(StringUtils.hasText(value), "required live environment is absent: " + name);
        return value;
    }

    private LiveRuntime liveRuntime() {
        Assumptions.assumeTrue("true".equals(System.getenv("SESSION_AGENT_LIVE")), "live opt-in is absent");
        requiredEnvironment("GOOGLE_API_KEY");
        return new LiveRuntime(requiredEnvironment("SESSION_AGENT_BASE_URL"));
    }

    private String requiredHistorySessionKey() {
        Optional<String> sessionKey = externalHistorySessionKey(System.getenv("SESSION_AGENT_HISTORY_KEY"));
        Assumptions.assumeTrue(sessionKey.isPresent(), "SESSION_AGENT_HISTORY_KEY is absent");
        return sessionKey.orElseThrow();
    }

    private static Optional<String> externalHistorySessionKey(String sessionKey) {
        if (StringUtils.hasText(sessionKey)) {
            return Optional.of(sessionKey);
        }
        return Optional.empty();
    }

    private ToolResult currentCatalog(ScenarioState state) {
        return state.toolResults().stream()
                .filter(tool -> tool.toolName().equals("list_repositories"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session did not record a repository catalog"));
    }

    private String catalogRevision(ToolResult catalog, String repositoryId) {
        JsonNode repositories = parseStructuredJson(catalog.resultJson()).path("data").path("repositories");
        for (JsonNode repository : repositories) {
            if (repositoryId.equals(requiredText(repository, "repositoryId"))) {
                return requiredText(repository, "revision");
            }
        }
        throw new AssertionError("repository catalog did not include " + repositoryId);
    }

    private JsonNode revisionOutdatedFeedback(ScenarioState state) {
        return state.feedbackMessages().stream()
                .filter(message -> "REVISION_OUTDATED".equals(requiredText(message, "feedbackCode")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session did not record a revision-outdated tool failure"));
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

        private LiveRuntime(String runtimeBaseUrl) {
            this.runtimeBaseUrl = removeTrailingSlash(runtimeBaseUrl);
        }

        private ScenarioState ask(String sessionKey, String question) throws Exception {
            String sourceMessageId = UUID.randomUUID().toString();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("sessionKey", sessionKey);
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
            List<JsonNode> feedbackMessages = new ArrayList<>();
            JsonNode assistant = objectMapper.createObjectNode();
            for (JsonNode message : messages) {
                String role = requiredText(message, "role");
                assertThat(message.has("runtimeCallOrdinal")).isFalse();
                assertThat(message.has("providerAttempt")).isFalse();
                assertThat(message.has("rawPrompt")).isFalse();
                assertThat(message.has("rawCompletion")).isFalse();
                assertThat(message.has("providerError")).isFalse();
                assertThat(message.has("decodeError")).isFalse();
                switch (role) {
                    case "USER", "TOOL", "FEEDBACK", "ASSISTANT" -> {
                    }
                    default -> throw new AssertionError("unexpected session history role: " + role);
                }
                if (!belongsToJob(message, jobId)) {
                    continue;
                }
                switch (role) {
                    case "TOOL" -> toolMessages.add(message);
                    case "FEEDBACK" -> feedbackMessages.add(message);
                    case "ASSISTANT" -> assistant = message;
                    case "USER" -> throw new AssertionError("current-job history must not contain USER role");
                    default -> throw new AssertionError("unexpected current-job session history role: " + role);
                }
            }
            assertThat(toolMessages).isNotEmpty();
            assertThat(assistant.isObject()).isTrue();
            String assistantText = requiredText(assistant, "message");
            List<String> toolOrder = new ArrayList<>();
            List<ToolResult> toolResults = new ArrayList<>();
            Map<String, ToolResult> resultsById = new HashMap<>();
            List<RepositoryRevision> repositories = new ArrayList<>();
            Set<String> sourceRepositoryIds = new HashSet<>();
            for (JsonNode tool : toolMessages) {
                ToolResult toolResult = readToolResult(sessionId, tool);
                toolOrder.add(toolResult.toolName());
                toolResults.add(toolResult);
                resultsById.put(toolResult.resultId(), toolResult);
                if (toolResult.repositoryId().isPresent()) {
                    String requiredRepositoryId = toolResult.repositoryId().orElseThrow();
                    JsonNode arguments = parseStructuredJson(toolResult.canonicalArguments());
                    assertThat(requiredText(arguments, "repositoryId")).isEqualTo(requiredRepositoryId);
                    assertThat(toolResult.revision()).isPresent();
                    String requiredRevision = toolResult.revision().orElseThrow();
                    sourceRepositoryIds.add(requiredRepositoryId);
                    repositories.add(new RepositoryRevision(requiredRepositoryId, requiredRevision));
                }
            }
            List<String> citations = citationIds(assistant);
            assertThat(citations).isNotEmpty();
            Set<String> citedRepositoryIds = new HashSet<>();
            for (String citation : citations) {
                ToolResult citedResult = resultsById.get(citation);
                assertThat(citedResult).isNotNull();
                assertThat(citedResult.citeable()).isTrue();
                assertThat(citedResult.repositoryId()).isPresent();
                citedRepositoryIds.add(citedResult.repositoryId().orElseThrow());
            }
            return new ScenarioState(sessionId, jobId, assistantText, toolOrder, toolResults, repositories, citations, feedbackMessages,
                    resultsById, previousCatalog(sessionId, jobId, messages), sourceRepositoryIds, citedRepositoryIds);
        }

        private ToolResult readToolResult(String sessionId, JsonNode toolMessage) throws Exception {
            String resultId = requiredText(toolMessage, "resultId");
            JsonNode result = request("GET", runtimeBaseUrl + "/internal/results/" + resultId, Optional.empty());
            assertThat(requiredText(result, "sessionId")).isEqualTo(sessionId);
            Optional<String> repositoryId = optionalText(toolMessage, "repositoryId");
            Optional<String> revision = optionalText(toolMessage, "revision");
            if (revision.isPresent()) {
                assertThat(requiredText(result, "revision")).isEqualTo(revision.orElseThrow());
            }
            return new ToolResult(requiredSequence(toolMessage), resultId, requiredText(toolMessage, "toolName"), repositoryId, revision,
                    result.path("citeable").asBoolean(false), requiredText(result, "canonicalArguments"), requiredText(result, "resultJson"));
        }

        private Optional<ToolResult> previousCatalog(String sessionId, String jobId, JsonNode messages) throws Exception {
            List<JsonNode> catalogMessages = new ArrayList<>();
            for (JsonNode message : messages) {
                if (!belongsToJob(message, jobId)
                        && "TOOL".equals(requiredText(message, "role"))
                        && "list_repositories".equals(requiredText(message, "toolName"))) {
                    catalogMessages.add(message);
                }
            }
            if (catalogMessages.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(readToolResult(sessionId, catalogMessages.getLast()));
        }

        private boolean belongsToJob(JsonNode message, String jobId) {
            return optionalText(message, "messageJobId").filter(jobId::equals).isPresent();
        }

        private JsonNode request(String method, String url, Optional<JsonNode> payload) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json");
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

    private boolean hasFullCoverageEmptyResult(String resultJson) {
        JsonNode data = parseStructuredJson(resultJson).path("data");
        return data.path("totalCount").asLong(-1) == 0
                && !data.path("hasMore").asBoolean(true)
                && data.path("coverage").path("issues").isArray()
                && data.path("coverage").path("issues").isEmpty();
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

    private long requiredSequence(JsonNode message) {
        JsonNode sequence = message.path("sequence");
        assertThat(sequence.isIntegralNumber()).isTrue();
        return sequence.asLong();
    }

    private Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual() && StringUtils.hasText(value.asText())) {
            return Optional.of(value.asText());
        }
        return Optional.empty();
    }

    private String removeTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record ScenarioState(
            String sessionId,
            String jobId,
            String assistantText,
            List<String> toolOrder,
            List<ToolResult> toolResults,
            List<RepositoryRevision> repositories,
            List<String> citations,
            List<JsonNode> feedbackMessages,
            Map<String, ToolResult> resultsById,
            Optional<ToolResult> previousCatalog,
            Set<String> sourceRepositoryIds,
            Set<String> citedRepositoryIds) {

        private ToolResult resultById(String resultId) {
            return Optional.ofNullable(resultsById.get(resultId))
                    .orElseThrow(() -> new AssertionError("citation did not reference a successful result from this message job"));
        }

        private ScenarioReport toReport(String outcome) {
            return new ScenarioReport(sessionId, jobId, toolOrder, repositories, citations, outcome);
        }
    }

    private record ToolResult(
            long messageSequence,
            String resultId,
            String toolName,
            Optional<String> repositoryId,
            Optional<String> revision,
            boolean citeable,
            String canonicalArguments,
            String resultJson) {
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
