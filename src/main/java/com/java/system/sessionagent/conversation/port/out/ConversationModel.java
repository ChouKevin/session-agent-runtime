package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;

import java.util.function.Consumer;

@FunctionalInterface
public interface ConversationModel {

    ModelDecision decide(ModelRequest request, Consumer<ModelUsage> usageObserver);
}
