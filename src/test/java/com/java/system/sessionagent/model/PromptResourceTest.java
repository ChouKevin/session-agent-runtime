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
                .contains("entries are useful but not citeable")
                .contains("`codebase_search_code_facts`")
                .contains("REVISION_OUTDATED")
                .contains("one tool call per response")
                .contains("runtime-only")
                .contains("totalCount:0")
                .contains("hasMore:false")
                .contains("coverage.issues")
                .contains("codebase does not contain")
                .contains("Do not conclude that the product currently")
                .contains("MUST include that search result's exact `resultId`")
                .contains("\"message\":\"<answer>\"")
                .contains("\"citations\":[{\"value\":\"<resultId>\"}]");
    }
}
