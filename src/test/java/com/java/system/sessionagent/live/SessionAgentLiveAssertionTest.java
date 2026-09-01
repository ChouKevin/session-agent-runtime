package com.java.system.sessionagent.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
    void accepts_an_exact_fee_that_depends_on_runtime_configuration() {
        assertThatCode(() -> SessionAgentLiveIT.assertHonestRuntimeFeeAnswer(
                "Fees use a formula loaded from runtime storage. The exact fees depend on the current environment's configuration."))
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_a_runtime_fee_answer_that_says_specific_amounts_are_not_in_source() {
        assertThatCode(() -> SessionAgentLiveIT.assertHonestRuntimeFeeAnswer(
                "Fees are calculated dynamically at runtime from a JSON formula. "
                        + "The specific fee amounts are not hard-coded in the source code."))
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_fee_amounts_that_depend_on_deployment_configuration() {
        assertThatCode(() -> SessionAgentLiveIT.assertHonestRuntimeFeeAnswer(
                "The fee formula is loaded from runtime storage. "
                        + "The specific fee amounts or percentages depend on the deployment environment configuration."))
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
    void accepts_a_code_limited_bnpl_empty_search_finding() {
        assertThatCode(() -> SessionAgentLiveIT.assertCodeLimitedBnplAnswer(
                "The BNPL search yielded no results. Based on the inspected code, payment-service does not include BNPL."))
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_a_code_limited_bnpl_answer_with_qualified_support_wording() {
        assertThatCode(() -> SessionAgentLiveIT.assertCodeLimitedBnplAnswer(
                "Based on an inspection of the payment-service repository, the service does not appear to support BNPL. "
                        + "There is no mention of BNPL in the codebase."))
                .doesNotThrowAnyException();
    }

    @Test
    void accepts_a_code_limited_bnpl_answer_with_a_current_source_qualifier() {
        assertThatCode(() -> SessionAgentLiveIT.assertCodeLimitedBnplAnswer(
                "Based on codebase analysis of the payment-service repository, "
                        + "the service does not currently support BNPL. There are no references to BNPL in the codebase."))
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

    @Test
    void ignores_a_text_tool_failure_when_searching_for_a_source_observation() {
        JsonNode observation = OBJECT_MAPPER.createObjectNode()
                .put("input", "{\"repositoryId\":\"video-service\",\"revision\":\"abc123\"}")
                .put("output", "Tool execution failed.\nCode: TOOL_INPUT_INVALID");

        assertThatCode(() -> SessionAgentLiveIT.isRevisionPinnedSourceObservation(observation))
                .doesNotThrowAnyException();
        assertThat(SessionAgentLiveIT.isRevisionPinnedSourceObservation(observation))
                .isFalse();
    }

    @Test
    void ignores_an_incomplete_json_tool_result_when_searching_for_source_evidence() {
        JsonNode observation = OBJECT_MAPPER.createObjectNode()
                .put("input", "{\"repositoryId\":\"video-service\",\"revision\":\"abc123\"}")
                .put("output", "{\"message\":\"temporary failure\"}");

        assertThatCode(() -> SessionAgentLiveIT.isRevisionPinnedSourceObservation(observation))
                .doesNotThrowAnyException();
        assertThat(SessionAgentLiveIT.isRevisionPinnedSourceObservation(observation))
                .isFalse();
    }
}
