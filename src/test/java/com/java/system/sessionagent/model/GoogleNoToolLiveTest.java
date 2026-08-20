package com.java.system.sessionagent.model;

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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
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
