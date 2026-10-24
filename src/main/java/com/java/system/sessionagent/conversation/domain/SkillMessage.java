package com.java.system.sessionagent.conversation.domain;

import com.java.system.sessionagent.skill.domain.SkillResource;
import org.springframework.util.Assert;
import java.time.Instant;
import java.util.Optional;

public record SkillMessage(SessionId sessionId, SessionSequence sequence, Optional<MessageJobId> messageJobId,
                           Instant createdAt, MessageRole role, SkillResource resource) implements SessionMessage {
    public SkillMessage {
        Assert.notNull(sessionId, "Session ID must not be null");
        Assert.notNull(sequence, "Sequence must not be null");
        Assert.notNull(messageJobId, "Job ID must not be null");
        Assert.isTrue(messageJobId.isPresent(), "Skill event must belong to a job");
        Assert.notNull(createdAt, "Creation time must not be null");
        Assert.isTrue(role == MessageRole.SKILL, "Skill event must have SKILL role");
        Assert.notNull(resource, "Skill resource must not be null");
    }
}
