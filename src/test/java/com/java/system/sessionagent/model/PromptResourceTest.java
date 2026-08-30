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
    void defines_the_citation_free_tool_and_final_reply_contract() {
        PromptResource promptResource = new PromptResource();

        assertThat(promptResource.content())
                .contains("`list_repositories`")
                .contains("catalog identifies repositories")
                .contains("`codebase_search_code_facts`")
                .contains("REVISION_OUTDATED")
                .contains("Make only one tool call")
                .contains("available only at runtime")
                .contains("totalCount:0")
                .contains("hasMore:false")
                .contains("coverage.issues")
                .contains("codebase-limited absence finding")
                .contains("Do not turn that result into a product decision")
                .contains("Before making a cross-repository conclusion")
                .contains("absence of a call in one method proves only the inspected code path")
                .contains("downstream or runtime outcome")
                .doesNotContainIgnoringCase("citation")
                .doesNotContainIgnoringCase("citeable")
                .doesNotContain("\"message\":\"<answer>\"");
    }
}
