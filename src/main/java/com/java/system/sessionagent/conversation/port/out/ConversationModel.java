package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;

import java.util.function.Consumer;

public interface ConversationModel {

    ModelReply respond(
            ModelRequest request,
            ModelCallReservation reservation,
            Consumer<ModelUsage> usageObserver);
}
