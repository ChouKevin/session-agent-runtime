package com.java.system.sessionagent.model.adapter.springai;

import com.java.system.sessionagent.conversation.domain.ModelContinuation;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MessagePart;

import java.util.List;
import java.util.Optional;

public interface SpringAiContinuationHandler {

    ModelRouteId routeId();

    Optional<ModelContinuation> capture(AssistantMessage message);

    List<MessagePart> restore(ModelContinuation continuation);
}
