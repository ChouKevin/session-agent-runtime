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
        @DefaultValue("5s") Duration shutdownTimeout,
        @DefaultValue Delivery delivery) {

    @ConstructorBinding
    public SlackProperties {
        int configuredValues = configuredValueCount(appToken, botToken, botUserId);
        Assert.isTrue(configuredValues == 0 || configuredValues == 3,
                "Slack app token, bot token, and bot user ID must be configured together");
        Assert.notNull(reconnectDelay, "Slack reconnect delay must not be null");
        Assert.notNull(shutdownTimeout, "Slack shutdown timeout must not be null");
        Assert.notNull(delivery, "Slack delivery properties must not be null");
        Assert.isTrue(!reconnectDelay.isNegative() && !reconnectDelay.isZero(), "Slack reconnect delay must be positive");
        Assert.isTrue(!shutdownTimeout.isNegative() && !shutdownTimeout.isZero(), "Slack shutdown timeout must be positive");
    }

    public boolean enabled() {
        return StringUtils.hasText(appToken);
    }

    public SlackProperties(
            String appToken,
            String botToken,
            String botUserId,
            Duration reconnectDelay,
            Duration shutdownTimeout) {
        this(appToken, botToken, botUserId, reconnectDelay, shutdownTimeout, new Delivery());
    }

    public record Delivery(
            @DefaultValue("1s") Duration pollDelay,
            @DefaultValue("30s") Duration leaseDuration,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("60s") Duration maximumBackoff,
            @DefaultValue("5") int maximumAttempts) {

        public Delivery {
            Assert.notNull(pollDelay, "Slack delivery poll delay must not be null");
            Assert.notNull(leaseDuration, "Slack delivery lease duration must not be null");
            Assert.notNull(initialBackoff, "Slack delivery initial backoff must not be null");
            Assert.notNull(maximumBackoff, "Slack delivery maximum backoff must not be null");
            Assert.isTrue(!pollDelay.isNegative() && !pollDelay.isZero(), "Slack delivery poll delay must be positive");
            Assert.isTrue(!leaseDuration.isNegative() && !leaseDuration.isZero(), "Slack delivery lease duration must be positive");
            Assert.isTrue(!initialBackoff.isNegative() && !initialBackoff.isZero(), "Slack delivery initial backoff must be positive");
            Assert.isTrue(!maximumBackoff.isNegative() && !maximumBackoff.isZero(), "Slack delivery maximum backoff must be positive");
            Assert.isTrue(initialBackoff.compareTo(maximumBackoff) <= 0,
                    "Slack delivery initial backoff must not exceed maximum backoff");
            Assert.isTrue(maximumAttempts > 0, "Slack delivery maximum attempts must be positive");
        }

        public Delivery() {
            this(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(60), 5);
        }
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
