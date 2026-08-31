package com.java.system.sessionagent.tool.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFailureFeedbackTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void tells_the_model_to_change_only_the_outdated_revision() throws Exception {
        ToolExecutionFailure.RevisionOutdatedDetails details = new ToolExecutionFailure.RevisionOutdatedDetails(
                "payment-service", "revision-1", "revision-2", "Retry with currentRevision.");

        JsonNode feedback = JSON.readTree(ToolFailureFeedback.revisionOutdated(details));

        assertThat(feedback.required("message").textValue())
                .isEqualTo("Repository revision is outdated. Retry the same useful tool with currentRevision "
                        + "and keep every other argument unchanged.");
    }
}
