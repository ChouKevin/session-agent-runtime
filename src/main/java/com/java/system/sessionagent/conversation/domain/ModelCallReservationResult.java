package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

public sealed interface ModelCallReservationResult {
    record Reserved(int totalOrdinal, int executionOrdinal) implements ModelCallReservationResult {
        public Reserved {
            Assert.isTrue(totalOrdinal > 0 && executionOrdinal >= 0, "Model call ordinals must be valid");
        }
    }
    record BudgetExhausted() implements ModelCallReservationResult {}
    record AlreadyAttempted() implements ModelCallReservationResult {}
}
