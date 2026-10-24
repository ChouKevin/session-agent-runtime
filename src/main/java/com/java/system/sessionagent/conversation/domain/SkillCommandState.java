package com.java.system.sessionagent.conversation.domain;

import org.springframework.util.Assert;
import java.util.Optional;

public record SkillCommandState(MessageJobId jobId, SkillCommand command, Disposition disposition,
                                Optional<SessionSequence> resolutionSequence) {
    public enum Disposition { PENDING, ACCEPTED, REJECTED }

    public SkillCommandState {
        Assert.notNull(jobId, "Job ID must not be null");
        Assert.notNull(command, "Command must not be null");
        Assert.notNull(disposition, "Disposition must not be null");
        Assert.notNull(resolutionSequence, "Resolution must not be null");
        Assert.isTrue((disposition == Disposition.PENDING) == resolutionSequence.isEmpty(),
                "Only pending commands have no resolution");
    }
}
