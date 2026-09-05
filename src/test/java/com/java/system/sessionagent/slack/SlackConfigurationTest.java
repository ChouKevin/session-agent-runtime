package com.java.system.sessionagent.slack;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SlackConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SlackPropertiesConfiguration.class);

    @Test
    void disables_slack_when_all_credentials_are_absent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SlackProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void rejects_partial_slack_configuration() {
        contextRunner.withPropertyValues("session-agent.slack.app-token=xapp-partial")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void accepts_complete_socket_mode_configuration() {
        contextRunner.withPropertyValues(
                        "session-agent.slack.app-token=xapp-complete",
                        "session-agent.slack.bot-token=xoxb-complete",
                        "session-agent.slack.bot-user-id=UBOT")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SlackProperties.class).enabled()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SlackProperties.class)
    static class SlackPropertiesConfiguration {
    }
}
