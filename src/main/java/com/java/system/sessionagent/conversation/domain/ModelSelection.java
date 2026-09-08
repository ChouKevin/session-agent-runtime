package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;

/**
 * Immutable configured model identity copied to an accepted job. A session stores only the model key.
 */
public record ModelSelection(String model, String fingerprint) {

    public ModelSelection {
        new ModelRouteId(model);
        Assert.hasText(fingerprint, "Model fingerprint must not be blank");
    }
}
