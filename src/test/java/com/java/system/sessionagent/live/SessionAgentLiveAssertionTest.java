package com.java.system.sessionagent.live;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionAgentLiveAssertionTest {

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
}
