package com.java.system.sessionagent.skill.domain;

public sealed interface SkillReadResult {
    record Success(SkillResource resource) implements SkillReadResult { }
    record Failure(String code) implements SkillReadResult { }
}
