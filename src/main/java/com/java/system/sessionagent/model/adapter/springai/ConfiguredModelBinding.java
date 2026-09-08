package com.java.system.sessionagent.model.adapter.springai;

import com.java.system.sessionagent.conversation.port.out.ConversationModel;
import org.springframework.util.Assert;

public record ConfiguredModelBinding(String model, String fingerprint, ConversationModel conversationModel) {
    public ConfiguredModelBinding {
        Assert.hasText(model, "Configured model key must not be blank");
        Assert.hasText(fingerprint, "Configured model fingerprint must not be blank");
        Assert.notNull(conversationModel, "Configured conversation model must not be null");
    }
}
