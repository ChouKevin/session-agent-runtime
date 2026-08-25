package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageRole;
import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ResultId;
import com.java.system.sessionagent.conversation.domain.SessionId;
import com.java.system.sessionagent.conversation.domain.SessionSequence;
import com.java.system.sessionagent.conversation.domain.ToolMessage;
import com.java.system.sessionagent.conversation.port.out.NoOpConversationTelemetry;
import com.java.system.sessionagent.semantic.http.SemanticRepositoryClient;
import com.java.system.sessionagent.semantic.http.SemanticSourceClient;
import com.java.system.sessionagent.semantic.tool.SemanticToolProvider;
import com.java.system.sessionagent.tool.application.DirectToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = GoogleNoToolLiveTest.LiveApplication.class,
        properties = "spring.flyway.enabled=false")
@EnabledIfEnvironmentVariable(named = "GOOGLE_NO_TOOL_LIVE", matches = "true")
class GoogleNoToolLiveTest {

    private static final String CONVERSATION_ID = "google-no-tool-live";

    private final ChatModel provider;

    @Autowired
    GoogleNoToolLiveTest(ChatModel provider) {
        this.provider = provider;
    }

    @Test
    void returns_a_classifiable_response_from_one_call_with_memory_and_no_tools() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(8).build();
        List<Message> history = List.of(
                new UserMessage("有哪些付款方式？"),
                new AssistantMessage("先前已查看付款服務的程式資訊，現在可以整理回答。"));
        memory.add(CONVERSATION_ID, history);
        CountingChatModel countingModel = new CountingChatModel(provider);
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(memory).build();
        ChatClient chatClient = ChatClient.builder(countingModel)
                .defaultSystem(new PromptResource().content())
                .defaultAdvisors(memoryAdvisor)
                .build();

        ChatResponse response = chatClient.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .toolCallbacks(List.<ToolCallback>of())
                .user("請根據剛才的對話直接回答；目前沒有可用工具。")
                .call()
                .chatResponse();

        LiveOutcome outcome = classify(Objects.requireNonNull(response));
        Prompt sentPrompt = Objects.requireNonNull(countingModel.lastPrompt());
        ChatOptions options = sentPrompt.getOptions();

        assertThat(countingModel.calls()).isEqualTo(1);
        assertThat(options).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(CollectionUtils.isEmpty(((ToolCallingChatOptions) options).getToolCallbacks())).isTrue();
        assertThat(outcome).isIn(LiveOutcome.REPLY, LiveOutcome.TOOL_CALL);

        System.out.printf("GOOGLE_NO_TOOL_LIVE_RESULT=%s usageAvailable=%s%n",
                outcome, usageAvailable(response));
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
        com.java.system.sessionagent.conversation.domain.UserMessage question =
                new com.java.system.sessionagent.conversation.domain.UserMessage(
                        sessionId, new SessionSequence(1), Optional.of(jobId), createdAt,
                        MessageRole.USER, "Alice", "有哪些付款方式？請先呼叫 list_repositories，不要直接回答。");

        ModelDecision firstDecision = model.decide(
                new ModelRequest(List.of(question), registry.snapshot(), false), usage -> { });
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

        ModelDecision secondDecision = model.decide(
                new ModelRequest(List.of(question, catalogResult), registry.snapshot(), false), usage -> { });

        assertThat(secondDecision).isInstanceOfAny(ModelDecision.UseTool.class, ModelDecision.Reply.class);
        System.out.printf("GOOGLE_ALL_TOOLS_LIVE_RESULT=%s%n", secondDecision.getClass().getSimpleName());
    }

    private static LiveOutcome classify(ChatResponse response) {
        return response.getResults().stream()
                .findFirst()
                .map(Generation::getOutput)
                .map(output -> {
                    if (output.hasToolCalls()) {
                        return LiveOutcome.TOOL_CALL;
                    }
                    return StringUtils.hasText(output.getText())
                            ? LiveOutcome.REPLY
                            : LiveOutcome.EMPTY_OR_INVALID;
                })
                .orElse(LiveOutcome.EMPTY_OR_INVALID);
    }

    private static boolean usageAvailable(ChatResponse response) {
        return Objects.nonNull(response.getMetadata().getUsage())
                && Objects.nonNull(response.getMetadata().getUsage().getTotalTokens());
    }

    private enum LiveOutcome {
        REPLY,
        TOOL_CALL,
        EMPTY_OR_INVALID
    }

    private static final class CountingChatModel implements ChatModel {

        private final ChatModel delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();

        private CountingChatModel(ChatModel delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt.set(prompt);
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

        private Prompt lastPrompt() {
            return lastPrompt.get();
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class LiveApplication {
    }
}
