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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "SESSION_AGENT_LIVE", matches = "true")
class SessionAgentLiveIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(4);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Test
    void completes_a_real_http_semantic_and_model_conversation() throws Exception {
        String baseUrl = requiredBaseUrl();
        String sourceMessageId = UUID.randomUUID().toString();
        JsonNode receipt = request(baseUrl, "POST", "/internal/messages", objectMapper.readTree("""
                {"sessionKey":"live-%s","participantId":"live-tester","sourceMessageId":"%s", "message":"What repositories are available? Use the available tools when needed."}
                """.formatted(sourceMessageId, sourceMessageId)));
        String sessionId = receipt.required("sessionId").asText();
        String jobId = receipt.required("messageJobId").asText();

        JsonNode job = waitForTerminalJob(baseUrl, jobId);
        JsonNode messages = request(baseUrl, "GET", "/internal/sessions/" + sessionId + "/messages", null);

        assertThat(job.required("status").asText()).isEqualTo("DONE");
        assertThat(messages.isArray()).isTrue();
        boolean observedSemanticTool = false;
        for (JsonNode message : messages) {
            if ("TOOL".equals(message.required("type").asText())) {
                observedSemanticTool = true;
            }
        }
        assertThat(observedSemanticTool).isTrue();
    }

    private String requiredBaseUrl() {
        String baseUrl = System.getenv("SESSION_AGENT_LIVE_BASE_URL");
        Assumptions.assumeTrue(StringUtils.hasText(baseUrl), "SESSION_AGENT_LIVE_BASE_URL is required");
        return baseUrl;
    }

    private JsonNode waitForTerminalJob(String baseUrl, String jobId) throws Exception {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode job = request(baseUrl, "GET", "/internal/message-jobs/" + jobId, null);
            if ("DONE".equals(job.required("status").asText())) {
                return job;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Live message job did not complete before timeout");
    }

    private JsonNode request(String baseUrl, String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (body == null) { // cs-allow request body is optional by HTTP method
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        return objectMapper.readTree(response.body());
    }
}
