package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.MessageJobId;
import com.java.system.sessionagent.conversation.domain.MessageReceipt;
import com.java.system.sessionagent.conversation.domain.SessionId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackEventAdapterTest {

    @Test
    void accepts_an_addressed_channel_root_after_durable_intake() {
        AtomicReference<SlackRootIntake> accepted = new AtomicReference<>();
        SlackRootIntakePort intakePort = intake -> {
            accepted.set(intake);
            return new MessageReceipt(new SessionId("session"), new MessageJobId("job"));
        };
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intakePort);

        SlackEventOutcome outcome = adapter.handle(new SlackRootEvent(
                "T1", "C1", "1.000001", "", "U1", "", "channel", "<@UBOT> hello", ""));

        assertThat(outcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(accepted.get().rootThreadTs()).isEqualTo("1.000001");
        assertThat(accepted.get().message().message()).isEqualTo("hello");
        assertThat(accepted.get().message().source().storageValue()).isEqualTo("slack");
    }

    @Test
    void accepts_a_nonblank_dm_without_a_mention_but_filters_blank_and_nonhuman_events() {
        AtomicReference<SlackRootIntake> accepted = new AtomicReference<>();
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intake -> {
            accepted.set(intake);
            return new MessageReceipt(new SessionId("session"), new MessageJobId("job"));
        });

        SlackEventOutcome dmOutcome = adapter.handle(new SlackRootEvent(
                "T1", "D1", "2.000001", "", "U1", "", "im", " hello ", ""));
        SlackEventOutcome blankChannelOutcome = adapter.handle(new SlackRootEvent(
                "T1", "C1", "3.000001", "", "U1", "", "channel", "<@UBOT>  ", ""));
        SlackEventOutcome botOutcome = adapter.handle(new SlackRootEvent(
                "T1", "D1", "4.000001", "", "UBOT", "", "im", "ignored", ""));
        SlackEventOutcome systemOutcome = adapter.handle(new SlackRootEvent(
                "T1", "C1", "5.000001", "", "U1", "", "channel", "<@UBOT> ignored", "channel_join"));

        assertThat(dmOutcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(accepted.get().message().message()).isEqualTo("hello");
        assertThat(blankChannelOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(botOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(systemOutcome).isEqualTo(SlackEventOutcome.IGNORED);
    }

    @Test
    void does_not_return_an_acknowledgement_when_durable_intake_fails() {
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intake -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThatThrownBy(() -> adapter.handle(new SlackRootEvent(
                "T1", "C1", "1.000001", "", "U1", "", "channel", "<@UBOT> hello", "")))
                .isInstanceOf(IllegalStateException.class);
    }
}
