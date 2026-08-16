package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelUsage;

import java.time.Duration;
import java.util.Optional;

public interface ConversationTelemetry {

    void intake(String outcome);

    void job(String outcome);

    void model(String outcome, Optional<String> finishReason, ModelUsage usage);

    void tool(String toolName, String outcome, Optional<String> repositoryId, Optional<String> revision);

    void feedback(String code);

    void retry(String dependency, Duration delay);
}
