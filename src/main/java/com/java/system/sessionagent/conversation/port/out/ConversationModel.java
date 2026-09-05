package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelDescriptor;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;

import java.util.function.Consumer;

public interface ConversationModel {

    ModelRouteId routeId();

    default ModelDescriptor descriptor() {
        return new ModelDescriptor(routeId(), "unspecified", 1);
    }

    default String systemPrompt() {
        return "Runtime system prompt";
    }

    default ModelCallResult respond(ModelRequest request, ModelCallReservation reservation) {
        return respond(request, reservation, usage -> { });
    }

    ModelCallResult respond(ModelRequest request, ModelCallReservation reservation, Consumer<ModelUsage> usageObserver);
}
