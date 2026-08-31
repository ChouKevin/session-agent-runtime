package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelUsage;

import java.time.Duration;
import java.util.Optional;

public interface ConversationTelemetry {

    void intake(String outcome);

    void job(String outcome);

    void model(String outcome, Optional<String> category, ModelUsage usage, Duration duration);

    void tool(String toolName, String outcome, Duration duration);

    void feedback(String code);

    void retry(String dependency, Duration delay);
}
