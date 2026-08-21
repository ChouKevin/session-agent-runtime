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
                .contains("query every relevant repository before answering")
                .contains("Missing behavior in one repository")
                .contains("no downstream or reactive behavior")
                .contains("from every repository used for the cross-repository conclusion")
                .contains("copy the complete sourceFile and range exactly")
                .contains("An interface or abstraction declaration alone does not prove")
                .contains("at most one targeted `codebase_discover_method_implementations` request")
                .contains("source literal or deterministic source formula")
                .contains("database, configuration provider, file, secret, user input, or external API")
                .contains("complete empty result")
                .contains("partial or unresolved result")
                .contains("use `codebase_discover_type_members` and then `codebase_get_method_source`")
                .contains("Never use `codebase_get_source_segment` to reconstruct a whole method or file")
                .contains("stop querying and answer")
                .contains("runtime value is unavailable")
                .contains("\"message\":\"<answer>\"")
                .contains("\"citations\":[{\"value\":\"<resultId>\"}]")
                .doesNotContain("is sufficient evidence that the current value is runtime-only")
                .doesNotContain("Do not inspect its implementation, callers, or references")
                .contains("Do not wrap the JSON in Markdown");
    }
}
