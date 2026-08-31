package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelUsage;

import java.time.Duration;
import java.util.Optional;

public final class NoOpConversationTelemetry implements ConversationTelemetry {

    @Override
    public void intake(String outcome) {
    }

    @Override
    public void job(String outcome) {
    }

    @Override
    public void model(String outcome, Optional<String> category, ModelUsage usage, Duration duration) {
    }

    @Override
    public void tool(String toolName, String outcome, Duration duration) {
    }

    @Override
    public void feedback(String code) {
    }

    @Override
    public void retry(String dependency, Duration delay) {
    }
}
