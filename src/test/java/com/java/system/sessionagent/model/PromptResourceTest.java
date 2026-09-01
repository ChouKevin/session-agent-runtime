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
    void defines_provider_neutral_multi_tool_execution_rules() {
        PromptResource promptResource = new PromptResource();

        assertThat(promptResource.content())
                .contains("Follow each tool")
                .contains("description and input schema")
                .contains("one or more tools")
                .contains("run sequentially in the order")
                .contains("One tool failure does not cancel")
                .contains("must be independent")
                .contains("Stop querying and answer")
                .contains("Do not repeat")
                .contains("equivalent request")
                .contains("external-service values")
                .doesNotContain("one tool call per")
                .doesNotContainIgnoringCase("planning")
                .doesNotContainIgnoringCase("final reply")
                .doesNotContain("`list_repositories`")
                .doesNotContain("`codebase_search_code_facts`")
                .doesNotContain("totalCount:0")
                .doesNotContain("REVISION_OUTDATED")
                .doesNotContainIgnoringCase("citation")
                .doesNotContainIgnoringCase("citeable")
                .doesNotContain("\"message\":\"<answer>\"");
    }
}
