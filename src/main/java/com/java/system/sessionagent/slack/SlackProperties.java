package com.java.system.sessionagent.slack;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties("session-agent.slack")
public record SlackProperties(
        @DefaultValue("") String appToken,
        @DefaultValue("") String botToken,
        @DefaultValue("") String botUserId,
        @DefaultValue("5s") Duration reconnectDelay,
        @DefaultValue("5s") Duration shutdownTimeout) {

    @ConstructorBinding
    public SlackProperties {
        int configuredValues = configuredValueCount(appToken, botToken, botUserId);
        Assert.isTrue(configuredValues == 0 || configuredValues == 3,
                "Slack app token, bot token, and bot user ID must be configured together");
        Assert.notNull(reconnectDelay, "Slack reconnect delay must not be null");
        Assert.notNull(shutdownTimeout, "Slack shutdown timeout must not be null");
        Assert.isTrue(!reconnectDelay.isNegative() && !reconnectDelay.isZero(), "Slack reconnect delay must be positive");
        Assert.isTrue(!shutdownTimeout.isNegative() && !shutdownTimeout.isZero(), "Slack shutdown timeout must be positive");
    }

    public boolean enabled() {
        return StringUtils.hasText(appToken);
    }

    private static int configuredValueCount(String appToken, String botToken, String botUserId) {
        int count = 0;
        if (StringUtils.hasText(appToken)) {
            count++;
        }
        if (StringUtils.hasText(botToken)) {
            count++;
        }
        if (StringUtils.hasText(botUserId)) {
            count++;
        }
        return count;
    }
}
