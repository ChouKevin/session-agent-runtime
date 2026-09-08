package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelSelection;

import java.util.Optional;

/**
 * Configured model identities and their provider-neutral conversation ports.
 */
public interface ConversationModels extends ModelSelectionLookup {

    ModelSelection defaultSelection();

    Optional<ConversationModel> resolve(ModelSelection selection);
}
