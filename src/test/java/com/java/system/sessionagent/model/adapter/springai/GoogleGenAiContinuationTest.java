package com.java.system.sessionagent.model.adapter.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.java.system.sessionagent.conversation.domain.AssistantToolCallsMessage;
import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolObservation;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.domain.JobControl;
import com.java.system.sessionagent.conversation.domain.JobStatus;
import com.java.system.sessionagent.conversation.domain.MessageWorkClaim;
import com.java.system.sessionagent.conversation.domain.ModelCallPurpose;
import com.java.system.sessionagent.conversation.domain.ModelCallReservationResult;
import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.domain.ConversationHistory;
import com.java.system.sessionagent.conversation.application.JobProcessor;
import com.java.system.sessionagent.conversation.application.MessageJobRetryPolicy;
import com.java.system.sessionagent.conversation.TestConfiguredModels;
import com.java.system.sessionagent.conversation.port.in.MessageJobProcessingResult;
import com.java.system.sessionagent.conversation.port.out.ConversationStore;
import com.java.system.sessionagent.skill.adapter.filesystem.FileSystemSkillLibrary;
import com.java.system.sessionagent.skill.configuration.SkillProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.tool.port.ToolSnapshot;
import com.sun.net.httpserver.HttpServer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleGenAiContinuationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replays_google_reasoning_text_and_each_signature_on_its_original_part_including_runtime_wrap(boolean wrapUp) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = requests.size() == 1 ? """
                    {"candidates":[{"content":{"role":"model","parts":[
                    {"thought":true,"text":"Need a lookup.","thoughtSignature":"AQID"},
                    {"executableCode":{"language":"PYTHON","code":"1+1"}},
                    {"text":"Checking.","thoughtSignature":"BAU="},
                    {"functionCall":{"id":"call-1","name":"lookup","args":{}},"thoughtSignature":"Bgc="},
                    {"text":"","thoughtSignature":"CAk="}]},"finishReason":"STOP"}]}
                    """ : """
                    {"candidates":[{"content":{"role":"model","parts":[{"text":"The fee is 9."}]},"finishReason":"STOP"}]}
                    """;
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (Client client = Client.builder().apiKey("local-fake-key")
                .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build()) {
            GoogleGenAiChatModel provider = GoogleGenAiChatModel.builder().genAiClient(client)
                    .options(GoogleGenAiChatOptions.builder().model("gemini-3.1-pro-preview").includeThoughts(true).build())
                    .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
            ObjectMapper mapper = new ObjectMapper();
            SpringAiConversationModel model = new SpringAiConversationModel(provider, new SystemPrompt(),
                    new NoOpConversationTelemetry(), mapper, new SpringAiMessagePartsHandler(new ModelRouteId("google-primary")));
            SessionId sessionId = new SessionId("session-1");
            Optional<MessageJobId> jobId = Optional.of(new MessageJobId("job-1"));
            UserMessage user = new UserMessage(sessionId, new SessionSequence(1), jobId, Instant.EPOCH,
                    MessageRole.USER, "alice", "find the fee");
            AtomicInteger reservations = new AtomicInteger();

            ModelCallResult first = model.respond(new ModelRequest(List.of(user), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER), reservations::incrementAndGet, ignored -> { });
            ModelReply.UseTools reply = (ModelReply.UseTools) first.reply();
            AssistantToolCallsMessage calls = new AssistantToolCallsMessage(sessionId, new SessionSequence(2), jobId,
                    Instant.EPOCH, MessageRole.ASSISTANT_TOOL_CALLS, reply.message(), reply.requests());
            ToolObservation observation = new ToolObservation(sessionId, new SessionSequence(3), jobId, Instant.EPOCH,
                    MessageRole.TOOL, reply.requests().getFirst().toolCallId(), "lookup", Map.of("isError", false, "result", Map.of("fee", 9)));
            if (wrapUp) {
                ConversationStore store = mock(ConversationStore.class);
                MessageWorkClaim claim = new MessageWorkClaim(jobId.orElseThrow(), sessionId,
                        new ModelSelection("test", "v1:test"), "worker", 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
                JobControl control = new JobControl(JobControl.Mode.NORMAL, JobControl.Stage.WRAP_UP,
                        user, Optional.empty(), 1, false, false);
                when(store.loadJobControl(claim)).thenReturn(control);
                when(store.enterWrapUp(eq(claim), any())).thenReturn(control);
                when(store.loadContextHistory(sessionId)).thenReturn(new ConversationHistory(List.of(user, calls, observation), Map.of()));
                when(store.loadContinuations(claim)).thenReturn(Map.of(calls.sequence(), first.continuation().orElseThrow()));
                when(store.reserveModelCall(eq(claim), eq(ModelCallPurpose.WRAP_UP), eq(1), any())).thenAnswer(invocation ->
                        new ModelCallReservationResult.Reserved(reservations.incrementAndGet(), 1));
                when(store.append(eq(claim), any(), any())).thenReturn(JobStatus.DONE);
                JobProcessor processor = new JobProcessor(store, TestConfiguredModels.withModel(model),
                        () -> { throw new AssertionError("Wrap must not open ordinary tools"); },
                        new FileSystemSkillLibrary(new SkillProperties()), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1,
                        new MessageJobRetryPolicy(3, Duration.ofNanos(1)), new NoOpConversationTelemetry());

                assertThat(processor.process(claim, () -> true)).isEqualTo(MessageJobProcessingResult.COMPLETED);
                ArgumentCaptor<ConversationStore.MessageBatch> batch = ArgumentCaptor.forClass(ConversationStore.MessageBatch.class);
                verify(store).append(eq(claim), batch.capture(), any());
                assertThat(batch.getValue().completionDisposition()).isEqualTo(ConversationStore.CompletionDisposition.WAITING);
                assertThat(batch.getValue().messages()).containsExactly(new ConversationStore.AssistantData("The fee is 9."));
            } else {
                ModelCallResult result = model.respond(new ModelRequest(List.of(user, calls, observation),
                        Map.of(calls.sequence(), first.continuation().orElseThrow()), new ToolSnapshot(List.of()), ModelRequest.Purpose.ANSWER),
                        reservations::incrementAndGet, ignored -> { });
                assertThat(result.reply()).isEqualTo(new ModelReply.Text("The fee is 9."));
            }

            assertThat(reply.message()).contains("Checking.");
            assertThat(requests).hasSize(2);
            assertThat(reservations).hasValue(2);
            JsonNode wire = mapper.readTree(requests.get(1));
            JsonNode parts = wire.at("/contents/1/parts");
            assertThat(parts.size()).isEqualTo(5);
            assertThat(parts.at("/0/thought").asBoolean()).isTrue();
            assertThat(parts.at("/0/text").asText()).isEqualTo("Need a lookup.");
            assertThat(parts.at("/0/thoughtSignature").asText()).isEqualTo("AQID");
            assertThat(parts.at("/1/executableCode/code").asText()).isEqualTo("1+1");
            assertThat(parts.at("/2/text").asText()).isEqualTo("Checking.");
            assertThat(parts.at("/2/thoughtSignature").asText()).isEqualTo("BAU=");
            assertThat(parts.at("/3/functionCall/id").asText()).isEqualTo("call-1");
            assertThat(parts.at("/3/thoughtSignature").asText()).isEqualTo("Bgc=");
            assertThat(parts.at("/4/thoughtSignature").asText()).isEqualTo("CAk=");
            assertThat(wire.at("/contents/2/parts/0/functionResponse/name").asText()).isEqualTo("lookup");
            assertThat(wire.at("/contents/2/parts/0/functionResponse/response/result/fee").asInt()).isEqualTo(9);
            if (wrapUp) {
                assertThat(wire.path("tools").isMissingNode() || wire.path("tools").isEmpty()).isTrue();
            }
        } finally {
            server.stop(0);
        }
    }
}
