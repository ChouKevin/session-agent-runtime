package com.java.system.sessionagent.conversation.domain;

import java.util.List;
import java.util.Map;

public record ConversationHistory(List<SessionMessage> messages, Map<MessageJobId, SkillCommandState> commands) {
    public ConversationHistory {
        messages = List.copyOf(messages);
        commands = Map.copyOf(commands);
    }
}
