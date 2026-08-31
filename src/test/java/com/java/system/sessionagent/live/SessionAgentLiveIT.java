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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "SESSION_AGENT_LIVE", matches = "true")
class SessionAgentLiveIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(4);
    private static final List<String> HISTORY_TYPES = List.of("USER", "TOOL", "ASSISTANT", "RUNTIME");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Test
    void records_payment_method_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("Which payment methods are supported? Inspect Semantic source before answering.");

        assertThat(scenario.tools()).isNotEmpty();
        assertThat(scenario.assistantText()).isNotBlank();
    }

    @Test
    void records_that_runtime_only_fee_data_is_unavailable() throws Exception {
        Scenario scenario = ask("What is the current runtime database/API fee value? Inspect the source and say clearly if that live value is unavailable.");

        assertThat(scenario.tools()).isNotEmpty();
        assertThat(scenario.assistantText().toLowerCase()).containsAnyOf("unavailable", "cannot", "not available");
    }

    @Test
    void records_video_format_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("Which video formats are supported? Inspect Semantic source before answering.");

        assertThat(scenario.tools()).isNotEmpty();
        assertThat(scenario.assistantText()).isNotBlank();
    }

    @Test
    void records_absent_bnpl_as_a_code_limited_finding() throws Exception {
        Scenario scenario = ask("Is BNPL supported? Search the inspected code and avoid making a claim about a running system.");

        assertThat(scenario.tools()).isNotEmpty();
        assertThat(scenario.assistantText().toLowerCase()).contains("bnpl");
    }

    @Test
    void records_cancellation_and_refund_source_inspection_in_http_history() throws Exception {
        Scenario scenario = ask("How do cancellation and refund work? Inspect Semantic source before answering.");

        assertThat(scenario.tools()).isNotEmpty();
        assertThat(scenario.assistantText()).isNotBlank();
    }

    @Test
    void records_a_failed_opaque_revision_observation_before_a_model_chosen_refresh_call() throws Exception {
        Scenario catalog = ask("List the available repositories through Semantic.");
        JsonNode catalogOutput = objectMapper.readTree(catalog.toolNamed("list_repositories").required("output").asText());
        JsonNode repository = catalogOutput.required("repositories").get(0);
        String repositoryId = repository.required("repositoryId").asText();
        String revision = repository.required("revision").asText();
        String staleRevision = revision + "-stale";

        Scenario refresh = ask("For repository " + repositoryId + ", first call codebase_list_entry_points with revision "
                + staleRevision + ". After its opaque failure observation, choose a fresh Semantic source tool call using current evidence.");
        JsonNode failedObservation = refresh.tools().stream()
                .filter(message -> message.required("output").asText().contains("SEMANTIC_REVISION_OUTDATED"))
                .findFirst().orElseThrow();
        long failedSequence = failedObservation.required("sequence").longValue();

        assertThat(refresh.tools()).anySatisfy(message -> {
            assertThat(message.required("sequence").longValue()).isGreaterThan(failedSequence);
            assertThat(message.required("input").asText()).doesNotContain(staleRevision);
        });
    }

    private Scenario ask(String question) throws Exception {
        String baseUrl = requiredBaseUrl();
        String sourceMessageId = UUID.randomUUID().toString();
        JsonNode receipt = request(baseUrl, "POST", "/internal/messages", Optional.of(objectMapper.readTree("""
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
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(value)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }

    private static List<JsonNode> toList(JsonNode messages) {
        java.util.ArrayList<JsonNode> history = new java.util.ArrayList<>();
        messages.elements().forEachRemaining(history::add);
        return List.copyOf(history);
    }

    private record Scenario(List<JsonNode> history) {

        private List<JsonNode> tools() {
            return history.stream().filter(message -> "TOOL".equals(message.required("type").asText())).toList();
        }

        private JsonNode toolNamed(String toolName) {
            return tools().stream().filter(message -> toolName.equals(message.required("toolName").asText())).findFirst().orElseThrow();
        }

        private String assistantText() {
            return history.stream().filter(message -> "ASSISTANT".equals(message.required("type").asText()))
                    .map(message -> message.required("message").asText()).reduce((first, second) -> second).orElseThrow();
        }
    }
}
