package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelCallContext;
import com.java.system.sessionagent.conversation.domain.ModelCallOutcome;
import com.java.system.sessionagent.conversation.domain.ModelCallPhase;
import com.java.system.sessionagent.conversation.domain.ModelCallRecord;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.domain.UserMessage;
import com.java.system.sessionagent.conversation.port.out.ModelCallRecorder;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = GoogleNoToolLiveTest.LiveApplication.class,
        properties = "spring.flyway.enabled=false")
@EnabledIfEnvironmentVariable(named = "GOOGLE_NO_TOOL_LIVE", matches = "true")
class GoogleNoToolLiveTest {

    private final ChatModel provider;

    @Autowired
    GoogleNoToolLiveTest(ChatModel provider) {
        this.provider = provider;
    }

    @Test
    void returns_a_cited_reply_from_the_real_two_phase_adapter_flow() {
        CountingChatModel countingModel = new CountingChatModel(provider);
        RecordingModelCallRecorder recorder = new RecordingModelCallRecorder();
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) provider.getOptions();
        GoogleConversationModel model = new GoogleConversationModel(
                countingModel, new PromptResource(), new NoOpConversationTelemetry(), recorder,
                Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC), options.getModel());
        SessionId sessionId = new SessionId("google-no-tool-live");
        MessageJobId jobId = new MessageJobId("google-no-tool-live-job");
        Instant createdAt = Instant.parse("2026-08-20T00:00:00Z");
        UserMessage question = new UserMessage(
                sessionId, new SessionSequence(1), Optional.of(jobId), createdAt, MessageRole.USER, "Alice",
                "Based only on source-result, what payment methods are supported? Answer directly and cite source-result.");
        ToolMessage source = new ToolMessage(
                sessionId, new SessionSequence(2), Optional.of(jobId), createdAt.plusSeconds(1), MessageRole.TOOL,
                new ResultId("source-result"), "source-call", "dGVzdA==", "codebase_list_entry_points", "1",
                "{\"repositoryId\":\"payment-service\",\"revision\":\"payment-revision-1\"}",
                Optional.of("payment-service"), Optional.of("payment-revision-1"),
                "{\"data\":{\"paymentMethods\":[\"credit card\",\"bank transfer\",\"wallet\"]}}", true);
        List<com.java.system.sessionagent.conversation.domain.SessionMessage> history = List.of(question, source);
        ModelRequest modelRequest = new ModelRequest(
                history, new DirectToolRegistry(List.of()).snapshot(), new ModelCallContext(sessionId, jobId, 1));
        ReplyRequest replyRequest = new ReplyRequest(history, new ModelCallContext(sessionId, jobId, 2));

        ModelDecision planning = model.plan(modelRequest, usage -> { });
        assertThat(planning).isEqualTo(new ModelDecision.AnswerReady());
        String reply = model.reply(replyRequest, usage -> { });

        assertThat(reply).isNotBlank();
        assertThat(countingModel.calls()).isEqualTo(2);
        assertThat(recorder.records()).satisfiesExactly(
                planningRecord -> {
                    assertThat(planningRecord.messageJobId()).isEqualTo(jobId);
                    assertThat(planningRecord.runtimeCallOrdinal()).isEqualTo(1);
                    assertThat(planningRecord.providerAttempt()).isEqualTo(1);
                    assertThat(planningRecord.phase()).isEqualTo(ModelCallPhase.PLAN);
                    assertThat(planningRecord.outcome()).isEqualTo(ModelCallOutcome.ANSWER_READY);
                },
                replyRecord -> {
                    assertThat(replyRecord.messageJobId()).isEqualTo(jobId);
                    assertThat(replyRecord.runtimeCallOrdinal()).isEqualTo(2);
                    assertThat(replyRecord.providerAttempt()).isEqualTo(1);
                    assertThat(replyRecord.phase()).isEqualTo(ModelCallPhase.FINAL_REPLY);
                    assertThat(replyRecord.outcome()).isEqualTo(ModelCallOutcome.FINAL_REPLY);
                });

        System.out.printf("GOOGLE_NO_TOOL_LIVE_RESULT=ANSWER_READY_AND_FINAL_REPLY%n");
    }

    @Test
    void preserves_catalog_thought_signature_before_exposing_source_tools() {
        RestClient semanticClient = RestClient.create("https://semantic.invalid");
        SemanticToolProvider toolProvider = new SemanticToolProvider(
                List::of,
                new SemanticSourceClient(semanticClient));
        DirectToolRegistry registry = new DirectToolRegistry(toolProvider.registrations());
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) provider.getOptions();
        GoogleConversationModel model = new GoogleConversationModel(
                provider, new PromptResource(), new NoOpConversationTelemetry(), options.getModel());
        SessionId sessionId = new SessionId("google-tool-context-live");
        MessageJobId jobId = new MessageJobId("google-tool-context-job");
        Instant createdAt = Instant.parse("2026-08-20T00:00:00Z");
        UserMessage question =
                new UserMessage(
                        sessionId, new SessionSequence(1), Optional.of(jobId), createdAt,
                        MessageRole.USER, "Alice", "有哪些付款方式？請先呼叫 list_repositories，不要直接回答。");

        ModelDecision firstDecision = model.plan(
                new ModelRequest(List.of(question), registry.snapshot(), new ModelCallContext(sessionId, jobId, 1)), usage -> { });
        assertThat(firstDecision).isInstanceOf(ModelDecision.UseTool.class);
        ModelDecision.UseTool catalogRequest = (ModelDecision.UseTool) firstDecision;
        assertThat(catalogRequest.toolName().value()).isEqualTo("list_repositories");
        ToolMessage catalogResult = new ToolMessage(
                sessionId, new SessionSequence(2), Optional.of(jobId), createdAt.plusSeconds(1), MessageRole.TOOL,
                new ResultId("catalog-result"), catalogRequest.callId(), catalogRequest.modelContext(),
                catalogRequest.toolName().value(), "1", catalogRequest.arguments(), Optional.empty(), Optional.empty(),
                """
                        {"data":{"repositories":[{"displayName":"Payment Service","repositoryId":"payment-service"}]},"resultId":"catalog-result","toolName":"list_repositories"}
                        """, false);

        ModelDecision secondDecision = model.plan(
                new ModelRequest(List.of(question, catalogResult), registry.snapshot(), new ModelCallContext(sessionId, jobId, 2)), usage -> { });

        assertThat(secondDecision).isInstanceOfAny(ModelDecision.UseTool.class, ModelDecision.AnswerReady.class);
        System.out.printf("GOOGLE_ALL_TOOLS_LIVE_RESULT=%s%n", secondDecision.getClass().getSimpleName());
    }

    private static final class CountingChatModel implements ChatModel {

        private final ChatModel delegate;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingChatModel(ChatModel delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return delegate.call(prompt);
        }

        @Override
        public ChatOptions getOptions() {
            return delegate.getOptions();
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class RecordingModelCallRecorder implements ModelCallRecorder {

        private final List<ModelCallRecord> records = new ArrayList<>();

        @Override
        public void record(ModelCallRecord record) {
            records.add(record);
        }

        private List<ModelCallRecord> records() {
            return List.copyOf(records);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class LiveApplication {
    }
}
