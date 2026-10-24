package com.java.system.sessionagent.skill.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.Assert;
import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties("session-agent.skills")
public record SkillProperties(@DefaultValue List<Path> roots,
                              @DefaultValue("65536") int maxFileBytes,
                              @DefaultValue("128") int maxSkills) {
    public SkillProperties() {
        this(List.of(), 65536, 128);
    }

    @ConstructorBinding
    public SkillProperties {
        roots = List.copyOf(roots);
        Assert.isTrue(maxFileBytes > 0 && maxFileBytes < Integer.MAX_VALUE, "Skill byte limit must be positive and bounded");
        Assert.isTrue(maxSkills > 0, "Skill count limit must be positive");
    }
}
