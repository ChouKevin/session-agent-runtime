package com.java.system.sessionagent.conversation.port.in;

import com.java.system.sessionagent.conversation.domain.SessionId;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves provider-owned external references without assigning provider semantics to the Runtime core.
 */
public interface ExternalSessionReferenceQueryPort {

    Optional<SessionId> findSessionId(String reference);

    Optional<ExternalSessionBindingView> findBinding(SessionId sessionId);

    record ExternalSessionBindingView(
            String source,
            String workspaceId,
            String conversationId,
            String rootMessageId,
            Instant createdAt) {

        public ExternalSessionBindingView {
            Assert.hasText(source, "External binding source must not be blank");
            Assert.hasText(workspaceId, "External binding workspace ID must not be blank");
            Assert.hasText(conversationId, "External binding conversation ID must not be blank");
            Assert.hasText(rootMessageId, "External binding root message ID must not be blank");
            Assert.notNull(createdAt, "External binding creation time must not be null");
        }
    }
}
