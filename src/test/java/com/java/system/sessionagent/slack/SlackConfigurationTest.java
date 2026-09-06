package com.java.system.sessionagent.slack;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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

    @Test
    void disables_bolt_subtype_auto_ack_so_candidate_subtypes_reach_durable_intake() {
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", ignored -> SlackEventOutcome.IGNORED);
        SlackBoltSocketClient client = new SlackBoltSocketClient(new SlackProperties("xapp-complete", "xoxb-complete", "UBOT",
                Duration.ofSeconds(1), Duration.ofSeconds(1)), adapter);

        assertThat(client.buildApp().config().isSubtypedMessageEventsAutoAckEnabled()).isFalse();
    }

    @Test
    void rejects_a_delivery_call_timeout_that_can_outlive_its_lease() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SlackProperties.Delivery(
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(60), 5));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SlackProperties.class)
    static class SlackPropertiesConfiguration {
    }
}
