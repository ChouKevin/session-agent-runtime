package com.java.system.sessionagent.conversation.port.in;

import java.util.Optional;

public record ConversationResultView(
        String resultId,
        String sessionId,
        String toolName,
        String toolVersion,
        String canonicalArguments,
        Optional<String> repositoryId,
        Optional<String> revision,
        String resultJson,
        boolean citeable) {
}
