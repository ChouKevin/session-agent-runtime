package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelDecision;
import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.domain.ReplyRequest;

import java.util.function.Consumer;

public interface ConversationModel {

    default ModelReply respond(
            ModelRequest request,
            ModelCallReservation reservation,
            Consumer<ModelUsage> usageObserver) {
        throw new UnsupportedOperationException("respond is not implemented");
    }

    default ModelDecision plan(ModelRequest request, Consumer<ModelUsage> usageObserver) {
        throw new UnsupportedOperationException("plan is not implemented");
    }

    default String reply(ReplyRequest request, Consumer<ModelUsage> usageObserver) {
        throw new UnsupportedOperationException("reply is not implemented");
    }
}
