package com.java.system.sessionagent.conversation.port.out;

import com.java.system.sessionagent.conversation.domain.ModelSelection;

import java.util.Optional;

@FunctionalInterface
public interface ModelSelectionLookup {

    Optional<ModelSelection> findSelection(String model);
}
