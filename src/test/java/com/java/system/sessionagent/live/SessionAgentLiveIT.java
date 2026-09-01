package com.java.system.sessionagent.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "SESSION_AGENT_LIVE", matches = "true")
class SessionAgentLiveIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(4);
    private static final List<String> HISTORY_TYPES = List.of("USER", "TOOL", "ASSISTANT", "RUNTIME");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Test
    @Tag("business-live")
    void records_payment_method_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("Which payment methods are supported?");

        JsonNode observation = scenario.successfulSourceObservationWithEvidence("credit card", "bank transfer", "wallet");
        assertRevisionPinnedSourceObservation(observation);
        assertTextContains(scenario.finalAssistantText(), "credit card", "bank transfer", "wallet");
    }

    @Test
    @Tag("business-live")
    void records_that_runtime_only_fee_data_is_unavailable() throws Exception {
        Scenario scenario = ask("Are there any payment processing fees, and how much are they?");

        JsonNode observation = scenario.successfulSourceObservationWithEvidence("fee", "formula");
        assertRevisionPinnedSourceObservation(observation);
        assertHonestRuntimeFeeAnswer(scenario.finalAssistantText());
    }

    @Test
    @Tag("business-live")
    void records_video_format_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("Which video formats are supported?");

        JsonNode observation = scenario.successfulSourceObservationWithEvidence("mp4", "webm", "mov");
        assertRevisionPinnedSourceObservation(observation);
        assertAnswerSharesFixtureFact(scenario.finalAssistantText(), observation, List.of("mp4", "webm", "mov", "hls", "dash", "m3u8"));
    }

    @Test
    @Tag("business-live")
    void records_absent_bnpl_as_a_code_limited_finding() throws Exception {
        Scenario scenario = ask("Does the payment service support buy now, pay later (BNPL)?");

        JsonNode observation = scenario.successfulToolNamed("codebase_search_code_facts");
        assertRevisionPinnedSourceObservation(observation);
        JsonNode data = output(observation).required("data");
        assertThat(data.required("totalCount").isIntegralNumber()).isTrue();
        assertThat(data.required("totalCount").intValue()).isZero();
        assertThat(data.required("hasMore").isBoolean()).isTrue();
        assertThat(data.required("hasMore").booleanValue()).isFalse();
        assertThat(data.required("coverage").required("issues").isArray()).isTrue();
        assertThat(data.required("coverage").required("issues").isEmpty()).isTrue();
        assertCodeLimitedBnplAnswer(scenario.finalAssistantText());
    }

    @Test
    @Tag("business-live")
    void records_cancellation_and_refund_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("How do cancellation and refunds work?");

        JsonNode cancellationObservation = scenario.successfulSourceObservationWithEvidence("cancellation");
        JsonNode refundObservation = scenario.successfulSourceObservationWithEvidence("refund");
        assertRevisionPinnedSourceObservation(cancellationObservation);
        assertRevisionPinnedSourceObservation(refundObservation);
        assertTextContains(scenario.finalAssistantText(), "cancellation", "refund");
    }

    @Test
    @Tag("runtime-contract-live")
    void records_a_failed_opaque_revision_observation_before_a_model_chosen_refresh_call() throws Exception {
        Scenario catalog = ask("List the available repositories through Semantic.");
        JsonNode catalogOutput = output(catalog.toolNamed("list_repositories"));
        JsonNode repository = catalogOutput.required("repositories").get(0);
        String repositoryId = repository.required("repositoryId").asText();
        String revision = repository.required("revision").asText();
        String replacement = "0".equals(revision.substring(0, 1)) ? "1" : "0";
        String staleRevision = replacement + revision.substring(1);

        Scenario refresh = ask("For repository " + repositoryId + ", first call codebase_list_entry_points with revision "
                + staleRevision + ". After its opaque failure observation, call list_repositories to obtain current Semantic "
                + "evidence, then choose a fresh codebase_list_entry_points call. State the repository ID and revision used in the final answer.");
        JsonNode failedObservation = refresh.tools().stream()
                .filter(message -> "codebase_list_entry_points".equals(message.required("toolName").asText()))
                .filter(message -> input(message).required("repositoryId").asText().equals(repositoryId))
                .filter(message -> input(message).required("revision").asText().equals(staleRevision))
                .filter(message -> message.required("output").asText().contains("SEMANTIC_REVISION_OUTDATED"))
                .findFirst().orElseThrow();
        long failedSequence = failedObservation.required("sequence").longValue();
        JsonNode refreshedCatalog = refresh.tools().stream()
                .filter(message -> message.required("sequence").longValue() > failedSequence)
                .filter(message -> "list_repositories".equals(message.required("toolName").asText()))
                .findFirst().orElseThrow();
        long refreshedCatalogSequence = refreshedCatalog.required("sequence").longValue();
        JsonNode refreshedCatalogRepository = toList(output(refreshedCatalog).required("repositories")).stream()
                .filter(value -> repositoryId.equals(value.required("repositoryId").asText())).findFirst().orElseThrow();
        String currentRevision = refreshedCatalogRepository.required("revision").asText();
        JsonNode refreshedObservation = refresh.tools().stream()
                .filter(message -> message.required("sequence").longValue() > refreshedCatalogSequence)
                .filter(message -> "codebase_list_entry_points".equals(message.required("toolName").asText()))
                .filter(message -> input(message).required("repositoryId").asText().equals(repositoryId))
                .filter(message -> input(message).required("revision").asText().equals(currentRevision))
                .filter(message -> !message.required("output").asText().contains("SEMANTIC_REVISION_OUTDATED"))
                .findFirst().orElseThrow();
        assertRevisionPinnedSourceObservation(refreshedObservation);
        assertThat(currentRevision).isNotEqualTo(staleRevision);
        assertTextContains(refresh.finalAssistantText(), repositoryId, currentRevision);
    }

    @Test
    @Tag("runtime-contract-live")
    void carries_prior_user_tool_and_assistant_messages_into_a_follow_up_in_the_same_session() throws Exception {
        String sessionKey = "live-history-" + UUID.randomUUID();
        String memoryLabel = "ORCHID-" + UUID.randomUUID().toString().substring(0, 8);
        Scenario firstTurn = ask(sessionKey, "Which payment methods are supported? Inspect Semantic source before answering. "
                + "Include this exact memory label in your answer: " + memoryLabel);

        JsonNode observation = firstTurn.successfulSourceObservationWithEvidence("credit card", "bank transfer", "wallet");
        assertRevisionPinnedSourceObservation(observation);
        assertTextContains(firstTurn.finalAssistantText(), memoryLabel);

        Scenario followUp = ask(sessionKey,
                "What exact memory label did I ask you to include previously? Answer only from our conversation history.");

        assertThat(followUp.sessionId()).isEqualTo(firstTurn.sessionId());
        assertThat(followUp.history()).startsWith(firstTurn.history().toArray(JsonNode[]::new));
        assertThat(followUp.history().stream().filter(message -> "USER".equals(message.required("type").asText())))
                .hasSize(2);
        assertTextContains(followUp.finalAssistantText(), memoryLabel);
        assertThat(firstTurn.finalAssistantText()).doesNotContain("Runtime tool observation");
        assertThat(followUp.finalAssistantText()).doesNotContain("Runtime tool observation");
    }

    private Scenario ask(String question) throws Exception {
        return ask("live-" + UUID.randomUUID(), question);
    }

    private Scenario ask(String sessionKey, String question) throws Exception {
        String baseUrl = requiredBaseUrl();
        String sourceMessageId = UUID.randomUUID().toString();
        JsonNode receipt = request(baseUrl, "POST", "/internal/messages", Optional.of(OBJECT_MAPPER.readTree("""
                {"sessionKey":"%s","participantId":"live-tester","sourceMessageId":"%s","message":"%s"}
                """.formatted(sessionKey, sourceMessageId, question))));
        String sessionId = receipt.required("sessionId").asText();
        String jobId = receipt.required("messageJobId").asText();
        JsonNode job = waitForTerminalJob(baseUrl, jobId);
        JsonNode messages = request(baseUrl, "GET", "/internal/sessions/" + sessionId + "/messages", Optional.empty());

        assertThat(job.required("status").asText()).isEqualTo("DONE");
        assertThat(messages.isArray()).isTrue();
        List<JsonNode> history = toList(messages);
        assertThat(history).isNotEmpty();
        assertThat(history).allSatisfy(message -> assertThat(message.required("type").asText()).isIn(HISTORY_TYPES));
        assertThat(history).extracting(message -> message.required("sequence").longValue()).isSorted();
        return new Scenario(sessionId, history);
    }

    private String requiredBaseUrl() {
        String baseUrl = System.getenv("SESSION_AGENT_BASE_URL");
        Assumptions.assumeTrue(StringUtils.hasText(baseUrl), "SESSION_AGENT_BASE_URL is required");
        return baseUrl;
    }

    private JsonNode waitForTerminalJob(String baseUrl, String jobId) throws Exception {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode job = request(baseUrl, "GET", "/internal/message-jobs/" + jobId, Optional.empty());
            if ("DONE".equals(job.required("status").asText())) {
                return job;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Live message job did not complete before timeout");
    }

    private JsonNode request(String baseUrl, String method, String path, Optional<JsonNode> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (body.isPresent()) {
            JsonNode value = body.orElseThrow();
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(value)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static List<JsonNode> toList(JsonNode messages) {
        java.util.ArrayList<JsonNode> history = new java.util.ArrayList<>();
        messages.elements().forEachRemaining(history::add);
        return List.copyOf(history);
    }

    static void assertRevisionPinnedSourceObservation(JsonNode observation) {
        JsonNode toolInput = input(observation);
        assertThat(toolInput.required("repositoryId").asText()).isNotBlank();
        assertThat(toolInput.required("revision").asText()).isNotBlank();
        JsonNode toolOutput = output(observation);
        assertThat(toolOutput.required("repositoryId").asText()).isEqualTo(toolInput.required("repositoryId").asText());
        assertThat(toolOutput.required("revision").asText()).isEqualTo(toolInput.required("revision").asText());
        assertThat(toolOutput.required("data").isContainerNode()).isTrue();
    }

    static boolean isRevisionPinnedSourceObservation(JsonNode observation) {
        try {
            JsonNode toolInput = input(observation);
            JsonNode toolOutput = output(observation);
            return toolInput.required("repositoryId").isTextual()
                    && toolInput.required("revision").isTextual()
                    && toolOutput.required("repositoryId").isTextual()
                    && toolOutput.required("revision").isTextual()
                    && toolOutput.required("data").isObject();
        } catch (AssertionError | IllegalArgumentException exception) {
            return false;
        }
    }

    private static void assertAnswerSharesFixtureFact(String answer, JsonNode observation, List<String> facts) {
        String evidence = lower(output(observation).toString());
        List<String> observedFacts = facts.stream().filter(evidence::contains).toList();
        assertThat(observedFacts).isNotEmpty();
        assertThat(lower(answer)).containsAnyOf(observedFacts.toArray(String[]::new));
    }

    private static void assertTextContains(String text, String... expectedTerms) {
        String normalized = normalizeWords(text);
        for (String expectedTerm : expectedTerms) {
            assertThat(normalized).contains(normalizeWords(expectedTerm));
        }
    }

    private static JsonNode input(JsonNode observation) {
        return readObservationJson(observation.required("input").asText());
    }

    private static JsonNode output(JsonNode observation) {
        return readObservationJson(observation.required("output").asText());
    }

    private static JsonNode readObservationJson(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception exception) {
            throw new AssertionError("Semantic observation was not JSON", exception);
        }
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeWords(String value) {
        return lower(value).replace('_', ' ');
    }

    static void assertHonestRuntimeFeeAnswer(String answer) {
        String normalized = lower(answer);
        boolean qualifiesFeeAmount = List.of(
                "unavailable", "cannot", "not available", "unable to obtain", "do not have",
                "depend on", "depends on", "dependent on", "determined by", "vary by", "varies by",
                "not hard-coded", "not present", "not defined", "not provided")
                .stream().anyMatch(normalized::contains);
        assertThat(normalized).contains("fee");
        assertThat(qualifiesFeeAmount)
                .as("answer must state that the exact fee is unavailable, runtime-dependent, or absent from source")
                .isTrue();
        assertThat(normalized).containsAnyOf("current", "runtime", "database", "api");
        assertThat(normalized).containsAnyOf("formula", "configuration", "json");
        assertThat(normalized).doesNotMatch("(?s).*\\bcurrent\\b[^.!?\\n]{0,80}\\bfee(?:\\s+value)?\\s*"
                + "(?:is|equals|=|:|of)\\s*(?:[$€£]\\s*)?\\d+(?:[.,]\\d+)?(?:\\s*%|\\s+percent)?\\b.*");
    }

    static void assertCodeLimitedBnplAnswer(String answer) {
        String normalized = lower(answer);
        assertThat(normalized).contains("bnpl");
        assertThat(normalized).containsAnyOf(
                "not found", "no results", "no bnpl", "no evidence", "not implemented", "does not support", "does not include",
                "does not appear to support", "does not currently support", "no mention");
        assertThat(normalized).containsAnyOf("inspected code", "codebase", "source code", "repository");
        assertThat(normalized).doesNotMatch("(?s).*\\bbnpl\\b.{0,120}\\b(?:is\\s+)?(?:not\\s+)?(?:supported|unsupported)\\b.{0,120}"
                + "\\b(?:running\\s+system|production(?:\\s+system)?|live\\s+system)\\b.*");
        assertThat(normalized).doesNotMatch("(?s).*\\b(?:running\\s+system|production(?:\\s+system)?|live\\s+system)\\b.{0,120}\\bbnpl\\b.{0,120}"
                + "\\b(?:is\\s+)?(?:not\\s+)?(?:supported|unsupported)\\b.*");
    }

    private record Scenario(String sessionId, List<JsonNode> history) {

        private List<JsonNode> tools() {
            return history.stream().filter(message -> "TOOL".equals(message.required("type").asText())).toList();
        }

        private JsonNode toolNamed(String toolName) {
            return tools().stream().filter(message -> toolName.equals(message.required("toolName").asText())).findFirst().orElseThrow();
        }

        private JsonNode successfulToolNamed(String toolName) {
            return tools().stream()
                    .filter(message -> toolName.equals(message.required("toolName").asText()))
                    .filter(message -> !message.required("output").asText().contains("SEMANTIC_"))
                    .filter(this::isRevisionPinnedSourceObservation)
                    .findFirst().orElseThrow();
        }

        private JsonNode successfulSourceObservationWithEvidence(String... requiredEvidence) {
            return tools().stream()
                    .filter(message -> message.required("toolName").asText().startsWith("codebase_"))
                    .filter(message -> !message.required("output").asText().contains("SEMANTIC_"))
                    .filter(this::isRevisionPinnedSourceObservation)
                    .filter(message -> containsAll(outputText(message), requiredEvidence))
                    .findFirst().orElseThrow();
        }

        private boolean isRevisionPinnedSourceObservation(JsonNode observation) {
            return SessionAgentLiveIT.isRevisionPinnedSourceObservation(observation);
        }

        private static String outputText(JsonNode observation) {
            return lower(observation.required("output").asText());
        }

        private static boolean containsAll(String value, String... expected) {
            String normalized = normalizeWords(value);
            for (String expectedValue : expected) {
                if (!normalized.contains(normalizeWords(expectedValue))) {
                    return false;
                }
            }
            return true;
        }

        private String finalAssistantText() {
            JsonNode terminalMessage = history.getLast();
            assertThat(terminalMessage.required("type").asText()).isEqualTo("ASSISTANT");
            String message = terminalMessage.required("message").asText();
            assertThat(message).isNotBlank();
            return message;
        }
    }
}
