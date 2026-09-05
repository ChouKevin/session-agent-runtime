package com.java.system.sessionagent.slack;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackEventAdapterTest {

    @Test
    void accepts_an_addressed_channel_root_after_durable_intake() {
        AtomicReference<SlackRootIntake> accepted = new AtomicReference<>();
        SlackRootIntakePort intakePort = intake -> {
            accepted.set(intake);
            return intake.classification() == SlackIntakeClassification.ACCEPTED
                    ? SlackEventOutcome.ACCEPTED : SlackEventOutcome.IGNORED;
        };
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intakePort);

        SlackEventOutcome outcome = adapter.handle(new SlackRootEvent(
                "E1", "T1", "C1", "1.000001", "", "U1", "", "channel",
                "<@UBOT> explain the literal token <@UBOT>", "", false, false));

        assertThat(outcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(accepted.get().rootThreadTs()).isEqualTo("1.000001");
        assertThat(accepted.get().message().orElseThrow().message()).isEqualTo("explain the literal token <@UBOT>");
        assertThat(accepted.get().message().orElseThrow().source().storageValue()).isEqualTo("slack");
    }

    @Test
    void accepts_a_nonblank_dm_without_a_mention_but_filters_blank_and_nonhuman_events() {
        AtomicReference<SlackRootIntake> accepted = new AtomicReference<>();
        List<SlackRootIntake> ignored = new ArrayList<>();
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intake -> {
            if (intake.classification() == SlackIntakeClassification.ACCEPTED) {
                accepted.set(intake);
            } else {
                ignored.add(intake);
            }
            return intake.classification() == SlackIntakeClassification.ACCEPTED
                    ? SlackEventOutcome.ACCEPTED : SlackEventOutcome.IGNORED;
        });

        SlackEventOutcome dmOutcome = adapter.handle(new SlackRootEvent(
                "E2", "T1", "D1", "2.000001", "", "U1", "", "im", " hello ", "", false, false));
        SlackEventOutcome blankChannelOutcome = adapter.handle(new SlackRootEvent(
                "E3", "T1", "C1", "3.000001", "", "U1", "", "channel", "<@UBOT>  ", "", false, false));
        SlackEventOutcome botOutcome = adapter.handle(new SlackRootEvent(
                "E4", "T1", "D1", "4.000001", "", "UBOT", "", "im", "ignored", "", false, false));
        SlackEventOutcome systemOutcome = adapter.handle(new SlackRootEvent(
                "E5", "T1", "C1", "5.000001", "", "U1", "", "channel", "<@UBOT> ignored", "channel_join", false, false));
        SlackEventOutcome subtypeOutcome = adapter.handle(new SlackRootEvent(
                "E6", "T1", "C1", "6.000001", "", "U1", "", "channel", "<@UBOT> ignored", "file_share", false, false));
        SlackEventOutcome hiddenOutcome = adapter.handle(new SlackRootEvent(
                "E7", "T1", "C1", "7.000001", "", "U1", "", "channel", "<@UBOT> ignored", "", true, false));
        SlackEventOutcome editOutcome = adapter.handle(new SlackRootEvent(
                "E8", "T1", "C1", "8.000001", "", "U1", "", "channel", "<@UBOT> ignored", "message_changed", false, false));
        SlackEventOutcome attachmentOutcome = adapter.handle(new SlackRootEvent(
                "E9", "T1", "C1", "9.000001", "", "U1", "", "channel", "<@UBOT> ignored", "", false, true));

        assertThat(dmOutcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(accepted.get().message().orElseThrow().message()).isEqualTo("hello");
        assertThat(blankChannelOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(botOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(systemOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(subtypeOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(hiddenOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(editOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(attachmentOutcome).isEqualTo(SlackEventOutcome.IGNORED);
        assertThat(ignored).extracting(SlackRootIntake::classification).containsExactly(
                SlackIntakeClassification.BLANK, SlackIntakeClassification.UNSUPPORTED_CONTENT,
                SlackIntakeClassification.HIDDEN, SlackIntakeClassification.EDIT_OR_DELETE,
                SlackIntakeClassification.UNSUPPORTED_CONTENT);
    }

    @Test
    void forwards_a_bound_thread_reply_without_an_addressing_mention() {
        AtomicReference<SlackRootIntake> accepted = new AtomicReference<>();
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intake -> {
            accepted.set(intake);
            return SlackEventOutcome.ACCEPTED;
        });

        SlackEventOutcome outcome = adapter.handle(new SlackRootEvent(
                "E-thread", "T1", "C1", "1.000002", "1.000001", "U2", "", "channel", "a reply", "", false, false));

        assertThat(outcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(accepted.get().rootThreadTs()).isEqualTo("1.000001");
        assertThat(accepted.get().message().orElseThrow().message()).isEqualTo("a reply");
    }

    @Test
    void does_not_return_an_acknowledgement_when_durable_intake_fails() {
        SlackEventAdapter adapter = new SlackEventAdapter("UBOT", intake -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThatThrownBy(() -> adapter.handle(new SlackRootEvent(
                "E1", "T1", "C1", "1.000001", "", "U1", "", "channel", "<@UBOT> hello", "", false, false)))
                .isInstanceOf(IllegalStateException.class);
    }
}
