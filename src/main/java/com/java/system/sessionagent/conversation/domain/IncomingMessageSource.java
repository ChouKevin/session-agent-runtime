package com.java.system.sessionagent.conversation.domain;

public enum IncomingMessageSource {
    HTTP("http"),
    SLACK("slack");

    private final String storageValue;

    IncomingMessageSource(String storageValue) {
        this.storageValue = storageValue;
    }

    public String storageValue() {
        return storageValue;
    }
}
