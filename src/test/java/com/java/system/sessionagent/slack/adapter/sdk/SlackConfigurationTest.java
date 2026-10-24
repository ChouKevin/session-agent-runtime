package com.java.system.sessionagent.slack.adapter.sdk;

import com.java.system.sessionagent.slack.application.MessageHandler;
import com.java.system.sessionagent.slack.configuration.SlackProperties;
import com.java.system.sessionagent.slack.configuration.SlackProgressProperties;
import com.java.system.sessionagent.slack.domain.SlackIntakeResult;

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
        MessageHandler adapter = new MessageHandler("UBOT", ignored -> SlackIntakeResult.newIgnored());
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

    @Test
    void binds_progress_once_and_rejects_nonpositive_scheduling_durations() {
        contextRunner.withPropertyValues(
                "session-agent.slack.progress.poll-delay=3s",
                "session-agent.slack.progress.refresh-interval=45s",
                "session-agent.slack.progress.workspace-interval=4s").run(context -> {
                    assertThat(context).hasSingleBean(SlackProgressProperties.class);
                    SlackProgressProperties progress = context.getBean(SlackProgressProperties.class);
                    assertThat(progress.pollDelay()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(progress.refreshInterval()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(progress.workspaceInterval()).isEqualTo(Duration.ofSeconds(4));
                });
        for (String property : java.util.List.of("poll-delay", "refresh-interval", "workspace-interval")) {
            contextRunner.withPropertyValues("session-agent.slack.progress." + property + "=0s")
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SlackProperties.class, SlackProgressProperties.class})
    static class SlackPropertiesConfiguration {
    }
}
