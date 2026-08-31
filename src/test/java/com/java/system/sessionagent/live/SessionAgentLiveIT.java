package com.java.system.sessionagent.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
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
    void records_payment_method_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("Which payment methods are supported? Inspect Semantic source before answering.");

        JsonNode observation = scenario.successfulSourceObservationWithEvidence("credit card", "bank transfer", "wallet");
        assertRevisionPinnedSourceObservation(observation);
        assertTextContains(scenario.finalAssistantText(), "credit card", "bank transfer", "wallet");
    }

    @Test
    void records_that_runtime_only_fee_data_is_unavailable() throws Exception {
        Scenario scenario = ask("What is the current runtime database/API fee value? Inspect the source and say clearly if that live value is unavailable.");

        JsonNode observation = scenario.successfulSourceObservationWithEvidence("fee", "formula");
        assertRevisionPinnedSourceObservation(observation);
        assertHonestRuntimeFeeAnswer(scenario.finalAssistantText());
    }

    @Test
    void records_video_format_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("Which video formats are supported? Inspect Semantic source before answering.");

        JsonNode observation = scenario.successfulSourceObservationWithEvidence("video");
        assertRevisionPinnedSourceObservation(observation);
        assertAnswerSharesFixtureFact(scenario.finalAssistantText(), observation, List.of("mp4", "webm", "hls", "dash", "m3u8"));
    }

    @Test
    void records_absent_bnpl_as_a_code_limited_finding() throws Exception {
        Scenario scenario = ask("Is BNPL supported? Search the inspected code and avoid making a claim about a running system.");

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
    void records_cancellation_and_refund_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("How do cancellation and refund work? Inspect Semantic source before answering.");

        JsonNode observation = scenario.successfulSourceObservationWithEvidence("cancellation", "refund");
        assertRevisionPinnedSourceObservation(observation);
        assertTextContains(scenario.finalAssistantText(), "cancellation", "refund");
    }

    @Test
    void records_a_failed_opaque_revision_observation_before_a_model_chosen_refresh_call() throws Exception {
        Scenario catalog = ask("List the available repositories through Semantic.");
        JsonNode catalogOutput = output(catalog.toolNamed("list_repositories"));
        JsonNode repository = catalogOutput.required("repositories").get(0);
        String repositoryId = repository.required("repositoryId").asText();
        String revision = repository.required("revision").asText();
        String staleRevision = revision + "-stale";

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

    private Scenario ask(String question) throws Exception {
        String baseUrl = requiredBaseUrl();
        String sourceMessageId = UUID.randomUUID().toString();
        JsonNode receipt = request(baseUrl, "POST", "/internal/messages", Optional.of(OBJECT_MAPPER.readTree("""
                {"sessionKey":"live-%s","participantId":"live-tester","sourceMessageId":"%s","message":"%s"}
                """.formatted(sourceMessageId, sourceMessageId, question))));
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
        return new Scenario(history);
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

    private static void assertRevisionPinnedSourceObservation(JsonNode observation) {
        JsonNode toolInput = input(observation);
        assertThat(toolInput.required("repositoryId").asText()).isNotBlank();
        assertThat(toolInput.required("revision").asText()).isNotBlank();
        JsonNode toolOutput = output(observation);
        assertThat(toolOutput.required("repositoryId").asText()).isEqualTo(toolInput.required("repositoryId").asText());
        assertThat(toolOutput.required("revision").asText()).isEqualTo(toolInput.required("revision").asText());
        assertThat(toolOutput.required("data").isObject()).isTrue();
    }

    private static void assertAnswerSharesFixtureFact(String answer, JsonNode observation, List<String> facts) {
        String evidence = lower(output(observation).toString());
        List<String> observedFacts = facts.stream().filter(evidence::contains).toList();
        assertThat(observedFacts).isNotEmpty();
        assertThat(lower(answer)).containsAnyOf(observedFacts.toArray(String[]::new));
    }

    private static void assertTextContains(String text, String... expectedTerms) {
        String normalized = lower(text);
        for (String expectedTerm : expectedTerms) {
            assertThat(normalized).contains(lower(expectedTerm));
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

    static void assertHonestRuntimeFeeAnswer(String answer) {
        String normalized = lower(answer);
        assertThat(normalized).containsAnyOf("unavailable", "cannot", "not available", "unable to obtain", "do not have");
        assertThat(normalized).containsAnyOf("current", "runtime", "database", "api");
        assertThat(normalized).containsAnyOf("formula", "configuration", "json");
        assertThat(normalized).doesNotMatch("(?s).*\\bcurrent(?:\\s+runtime)?(?:\\s+(?:database|api)(?:\\s*/\\s*(?:database|api))?)?\\s+fee(?:\\s+value)?\\s*(?:is|equals|=|:|of)\\s*"
                + "(?!(?:unavailable|not available|unknown|unobtainable|not known|not accessible|not provided)\\b)[^.!?\\n]+.*");
    }

    static void assertCodeLimitedBnplAnswer(String answer) {
        String normalized = lower(answer);
        assertThat(normalized).contains("bnpl");
        assertThat(normalized).containsAnyOf("not found", "no bnpl", "not implemented", "does not support");
        assertThat(normalized).containsAnyOf("inspected code", "codebase", "source code", "repository");
        assertThat(normalized).doesNotMatch("(?s).*\\bbnpl\\b.{0,120}\\b(?:is\\s+)?(?:not\\s+)?(?:supported|unsupported)\\b.{0,120}"
                + "\\b(?:running\\s+system|production(?:\\s+system)?|live\\s+system)\\b.*");
        assertThat(normalized).doesNotMatch("(?s).*\\b(?:running\\s+system|production(?:\\s+system)?|live\\s+system)\\b.{0,120}\\bbnpl\\b.{0,120}"
                + "\\b(?:is\\s+)?(?:not\\s+)?(?:supported|unsupported)\\b.*");
    }

    private record Scenario(List<JsonNode> history) {

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
            try {
                JsonNode toolInput = input(observation);
                JsonNode toolOutput = output(observation);
                return toolInput.required("repositoryId").isTextual()
                        && toolInput.required("revision").isTextual()
                        && toolOutput.required("repositoryId").isTextual()
                        && toolOutput.required("revision").isTextual()
                        && toolOutput.required("data").isObject();
            } catch (Exception exception) {
                return false;
            }
        }

        private static String outputText(JsonNode observation) {
            return lower(observation.required("output").asText());
        }

        private static boolean containsAll(String value, String... expected) {
            for (String expectedValue : expected) {
                if (!value.contains(lower(expectedValue))) {
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
