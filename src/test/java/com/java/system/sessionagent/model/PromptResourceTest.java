package com.java.system.sessionagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptResourceTest {

    @Test
    void loads_the_general_conversation_prompt_eagerly() {
        PromptResource promptResource = new PromptResource();

        assertThat(promptResource.content()).isNotBlank();
        assertThat(promptResource.content())
                .doesNotContain("list_entry_points")
                .doesNotContain("payment-service")
                .doesNotContain("order-service")
                .doesNotContain("repositoryId\":");
    }

    @Test
    void defines_the_exact_tool_and_final_reply_contract() {
        PromptResource promptResource = new PromptResource();

        assertThat(promptResource.content())
                .contains("`list_repositories`")
                .contains("catalog result is not citeable")
                .contains("Never repeat a rejected tool request with the same tool name and arguments")
                .contains("Follow-up operations are optional")
                .contains("copy the complete sourceFile and range exactly")
                .contains("stop querying and answer")
                .contains("runtime value is unavailable")
                .contains("\"message\":\"<answer>\"")
                .contains("\"citations\":[{\"value\":\"<resultId>\"}]")
                .contains("Do not wrap the JSON in Markdown");
    }
}
