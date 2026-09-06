package com.java.system.sessionagent.model.adapter.springai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptTest {

    @Test
    void loads_the_general_conversation_prompt_eagerly() {
        SystemPrompt promptResource = new SystemPrompt();

        assertThat(promptResource.content()).isNotBlank();
        assertThat(promptResource.content())
                .doesNotContain("list_entry_points")
                .doesNotContain("payment-service")
                .doesNotContain("order-service")
                .doesNotContain("repositoryId\":");
    }

    @Test
    void defines_only_the_durable_provider_neutral_tool_rules() {
        SystemPrompt promptResource = new SystemPrompt();

        assertThat(promptResource.content())
                .contains("external or codebase facts")
                .contains("identifiers exactly")
                .contains("sequentially")
                .contains("cannot depend on earlier results")
                .contains("failures are feedback")
                .contains("Do not invent facts")
                .doesNotContainIgnoringCase("schema")
                .doesNotContainIgnoringCase("repository")
                .doesNotContainIgnoringCase("fixture")
                .doesNotContainIgnoringCase("query count")
                .doesNotContainIgnoringCase("citation")
                .doesNotContainIgnoringCase("final format");
    }
}
