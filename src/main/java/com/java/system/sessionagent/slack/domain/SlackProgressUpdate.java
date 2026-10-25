package com.java.system.sessionagent.slack.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.util.Assert;

public record SlackProgressUpdate(long revision, SlackUpdateRequest request, boolean terminal, Instant observedAt,
        String sourceMessageTs, Optional<String> questionPermalink, Optional<SkillPresentation> skillPresentation) {
    public SlackProgressUpdate(long revision, SlackUpdateRequest request, boolean terminal, Instant observedAt,
            String sourceMessageTs, Optional<String> questionPermalink) {
        this(revision, request, terminal, observedAt, sourceMessageTs, questionPermalink, Optional.empty());
    }

    public SlackProgressUpdate {
        Assert.isTrue(revision > 0, "Progress revision must be positive");
        Objects.requireNonNull(request, "Update request must not be null");
        Objects.requireNonNull(observedAt, "Observation time must not be null");
        Assert.hasText(sourceMessageTs, "Source timestamp must not be blank");
        Objects.requireNonNull(questionPermalink, "Question permalink must not be null");
        Objects.requireNonNull(skillPresentation, "Skill presentation must not be null");
    }

    public record SkillPresentation(UUID attemptId, Kind kind, String skillName, ResourceKind resourceKind) {
        public enum Kind { STARTED, COMMITTED_SUCCESS, COMMITTED_FAILURE, INTERRUPTED }
        public enum ResourceKind { DEFINITION, REFERENCE }
    }
}
