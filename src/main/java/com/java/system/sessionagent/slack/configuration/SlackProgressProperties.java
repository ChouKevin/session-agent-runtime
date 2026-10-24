package com.java.system.sessionagent.slack.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.Assert;

@ConfigurationProperties("session-agent.slack.progress")
public record SlackProgressProperties(
        @DefaultValue("2s") Duration pollDelay,
        @DefaultValue("30s") Duration refreshInterval,
        @DefaultValue("2s") Duration workspaceInterval) {
    @ConstructorBinding
    public SlackProgressProperties {
        positive(pollDelay, "Poll delay");
        positive(refreshInterval, "Refresh interval");
        positive(workspaceInterval, "Workspace interval");
    }
    public SlackProgressProperties() {
        this(Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofSeconds(2));
    }
    private static void positive(Duration duration, String name) {
        Assert.notNull(duration, name + " must not be null");
        Assert.isTrue(!duration.isNegative() && !duration.isZero(), name + " must be positive");
    }
}
