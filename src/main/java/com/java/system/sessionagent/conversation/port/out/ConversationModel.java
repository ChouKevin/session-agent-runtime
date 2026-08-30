package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;

import java.util.function.Consumer;

public interface ConversationModel {

    ModelDecision plan(ModelRequest request, Consumer<ModelUsage> usageObserver);

    String reply(ReplyRequest request, Consumer<ModelUsage> usageObserver);
}
