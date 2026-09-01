package com.java.system.sessionagent.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionAgentLiveAssertionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void accepts_an_explicitly_unavailable_runtime_fee_value() {
        assertThatCode(() -> SessionAgentLiveIT.assertHonestRuntimeFeeAnswer(
                "The current runtime database/API fee value is not available in the source code. "
                        + "The fee formula configuration is externalized."))
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_a_markdown_runtime_fee_answer_using_or_between_sources() {
        assertThatCode(() -> SessionAgentLiveIT.assertHonestRuntimeFeeAnswer(
                "The current runtime database or API fee value is **unavailable** in the codebase. "
                        + "The fee formula is loaded dynamically."))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_a_claimed_current_runtime_fee_value() {
        assertThatThrownBy(() -> SessionAgentLiveIT.assertHonestRuntimeFeeAnswer(
                "The source formula is unavailable, but the current fee is $10."))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejects_a_running_system_bnpl_claim_despite_a_codebase_qualifier() {
        assertThatThrownBy(() -> SessionAgentLiveIT.assertCodeLimitedBnplAnswer(
                "BNPL is not supported in the running system; it was not found in inspected code."))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void accepts_a_code_limited_bnpl_no_evidence_finding() {
        assertThatCode(() -> SessionAgentLiveIT.assertCodeLimitedBnplAnswer(
                "There is no evidence that BNPL is supported in the inspected code repository."))
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_revision_pinned_source_observation_with_array_data() throws Exception {
        JsonNode observation = OBJECT_MAPPER.createObjectNode()
                .put("input", "{\"repositoryId\":\"order-service\",\"revision\":\"abc123\"}")
                .put("output", "{\"repositoryId\":\"order-service\",\"revision\":\"abc123\",\"data\":[]}");

        assertThatCode(() -> SessionAgentLiveIT.assertRevisionPinnedSourceObservation(observation))
                .doesNotThrowAnyException();
    }
}
