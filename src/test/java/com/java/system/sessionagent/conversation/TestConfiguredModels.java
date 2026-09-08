package com.java.system.sessionagent.conversation;

import com.java.system.sessionagent.conversation.domain.ModelSelection;
import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import com.java.system.sessionagent.conversation.port.out.ConversationModels;

import java.util.Optional;

public final class TestConfiguredModels {

    private TestConfiguredModels() {
    }

    public static ConversationModels withDefault(String model) {
        ModelSelection selection = new ModelSelection(model, "v1:test");
        return new ConversationModels() {
            @Override
            public ModelSelection defaultSelection() {
                return selection;
            }

            @Override
            public Optional<ModelSelection> findSelection(String key) {
                return selection.model().equals(key) ? Optional.of(selection) : Optional.empty();
            }

            @Override
            public Optional<ConversationModel> resolve(ModelSelection requestedSelection) {
                return Optional.empty();
            }
        };
    }
}
