package com.java.system.sessionagent.model;

import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Map;
import java.util.Optional;

public interface SpringAiContinuationHandler {

    ModelRouteId routeId();

    Optional<ModelContinuation> capture(AssistantMessage message);

    Map<String, Object> restore(ModelContinuation continuation);
}
