package com.java.system.sessionagent.acceptance;

import com.java.system.sessionagent.conversation.domain.ModelReply;
import com.java.system.sessionagent.conversation.domain.ModelCallResult;
import com.java.system.sessionagent.conversation.domain.ModelRequest;
import com.java.system.sessionagent.conversation.domain.ModelUsage;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ModelCallReservation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class FakeConversationModel implements ConversationModel {

    private final Deque<ModelReply> replies;
    private final List<ModelRequest> requests = new ArrayList<>();

    FakeConversationModel(List<ModelReply> replies) {
        this.replies = new ArrayDeque<>(List.copyOf(replies));
    }

    List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public ModelCallResult respond(
            ModelRequest request,
            ModelCallReservation reservation,
            Consumer<ModelUsage> usageObserver) {
        requests.add(request);
        reservation.reserve();
        return new ModelCallResult(Objects.requireNonNull(replies.pollFirst(), "No queued model reply is available"), java.util.Optional.empty());
    }
}
