package com.java.system.sessionagent.skill.port;

import com.java.system.sessionagent.skill.domain.SkillMetadata;
import com.java.system.sessionagent.skill.domain.SkillReadResult;
import java.util.List;

public interface SkillLibrary extends AutoCloseable {
    List<SkillMetadata> metadata();
    SkillReadResult read(String uri);
    @Override
    void close();
}
