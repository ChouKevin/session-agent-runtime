package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelRouteId;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;

import java.util.function.Consumer;

public interface ConversationModel {

    ModelRouteId routeId();

    ModelCallResult respond(
            ModelRequest request,
            ModelCallReservation reservation,
            Consumer<ModelUsage> usageObserver);
}
