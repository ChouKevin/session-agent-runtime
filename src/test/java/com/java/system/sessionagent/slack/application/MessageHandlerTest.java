package com.java.system.sessionagent.slack.application;

import com.java.system.sessionagent.slack.domain.IncomingEvent;
import com.java.system.sessionagent.slack.domain.MessageSubmission;
import com.java.system.sessionagent.slack.domain.SlackEventOutcome;
import com.java.system.sessionagent.slack.domain.SlackIntakeClassification;
import com.java.system.sessionagent.slack.domain.SlackIntakeResult;
import com.java.system.sessionagent.slack.port.out.MessageSubmissionPort;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageHandlerTest {

    @Test
    void recognizes_only_exact_mentioned_stop_commands_after_content_guards() {
        List<MessageSubmission> submissions = new ArrayList<>();
        MessageHandler handler = new MessageHandler("UBOT", intake -> {
            submissions.add(intake);
            return resultFor(intake);
        });
        List<String> commands = List.of(" <@UBOT> stop \n", "<@UBOT> 停止工作", "stop <@UBOT>");
        for (String text : commands) {
            handler.handle(new IncomingEvent("E-" + submissions.size(), "T1", "C1", "2", "1", "U2", "", "channel",
                    text, "", false, false));
        }
        handler.handle(new IncomingEvent("root", "T1", "C1", "3", "", "U2", "", "channel",
                "<@UBOT> stop", "", false, false));
        assertThat(submissions).allSatisfy(intake -> {
            assertThat(intake.classification()).isEqualTo(SlackIntakeClassification.STOP_REQUEST);
            assertThat(intake.message()).isEmpty();
        });
        assertThat(submissions.getLast().rootThreadTs()).isEqualTo("3");

        submissions.clear();
        for (String text : List.of("stop", "停止工作", "<@OTHER> stop", "<@UBOT> STOP", "<@UBOT> stop now",
                "<@UBOT> stop.", "<@UBOT> <@UBOT> stop")) {
            handler.handle(new IncomingEvent("ordinary-" + submissions.size(), "T1", "C1", "2", "1", "U2", "", "channel",
                    text, "", false, false));
        }
        assertThat(submissions).allSatisfy(intake ->
                assertThat(intake.classification()).isEqualTo(SlackIntakeClassification.ACCEPTED));
        submissions.clear();
        handler.handle(new IncomingEvent("attachment", "T1", "C1", "2", "1", "U2", "", "channel",
                "<@UBOT> stop", "", false, true));
        handler.handle(new IncomingEvent("edit", "T1", "C1", "2", "1", "U2", "", "channel",
                "<@UBOT> stop", "message_changed", false, false));
        handler.handle(new IncomingEvent("hidden", "T1", "C1", "2", "1", "U2", "", "channel",
                "<@UBOT> stop", "", true, false));
        handler.handle(new IncomingEvent("bot", "T1", "C1", "2", "1", "U2", "B1", "channel",
                "<@UBOT> stop", "", false, false));
        assertThat(submissions).extracting(MessageSubmission::classification).containsExactly(
                SlackIntakeClassification.UNSUPPORTED_CONTENT, SlackIntakeClassification.EDIT_OR_DELETE,
                SlackIntakeClassification.HIDDEN);
    }

    @Test
    void accepts_an_addressed_channel_root_after_durable_intake() {
        AtomicReference<MessageSubmission> accepted = new AtomicReference<>();
        MessageSubmissionPort intakePort = intake -> {
            accepted.set(intake);
            return resultFor(intake);
        };
        MessageHandler adapter = new MessageHandler("UBOT", intakePort);

        SlackEventOutcome outcome = adapter.handle(new IncomingEvent(
                "E1", "T1", "C1", "1.000001", "", "U1", "", "channel",
                "<@UBOT> explain the literal token <@UBOT>", "", false, false));

        assertThat(outcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(accepted.get().rootThreadTs()).isEqualTo("1.000001");
        assertThat(accepted.get().message().orElseThrow().message()).isEqualTo("explain the literal token <@UBOT>");
        assertThat(accepted.get().message().orElseThrow().source().storageValue()).isEqualTo("slack");
    }

    @Test
    void accepts_a_nonblank_dm_without_a_mention_but_filters_blank_and_nonhuman_events() {
        AtomicReference<MessageSubmission> accepted = new AtomicReference<>();
        List<MessageSubmission> ignored = new ArrayList<>();
        MessageHandler adapter = new MessageHandler("UBOT", intake -> {
            if (intake.classification() == SlackIntakeClassification.ACCEPTED) {
                accepted.set(intake);
            } else {
                ignored.add(intake);
            }
            return resultFor(intake);
        });

        SlackEventOutcome dmOutcome = adapter.handle(new IncomingEvent(
                "E2", "T1", "D1", "2.000001", "", "U1", "", "im", " hello ", "", false, false));
        SlackEventOutcome blankChannelOutcome = adapter.handle(new IncomingEvent(
                "E3", "T1", "C1", "3.000001", "", "U1", "", "channel", "<@UBOT>  ", "", false, false));
        SlackEventOutcome botOutcome = adapter.handle(new IncomingEvent(
                "E4", "T1", "D1", "4.000001", "", "UBOT", "", "im", "ignored", "", false, false));
        SlackEventOutcome systemOutcome = adapter.handle(new IncomingEvent(
                "E5", "T1", "C1", "5.000001", "", "U1", "", "channel", "<@UBOT> ignored", "channel_join", false, false));
        SlackEventOutcome subtypeOutcome = adapter.handle(new IncomingEvent(
                "E6", "T1", "C1", "6.000001", "", "U1", "", "channel", "<@UBOT> ignored", "file_share", false, false));
        SlackEventOutcome hiddenOutcome = adapter.handle(new IncomingEvent(
                "E7", "T1", "C1", "7.000001", "", "U1", "", "channel", "<@UBOT> ignored", "", true, false));
        SlackEventOutcome editOutcome = adapter.handle(new IncomingEvent(
                "E8", "T1", "C1", "8.000001", "", "U1", "", "channel", "<@UBOT> ignored", "message_changed", false, false));
        SlackEventOutcome attachmentOutcome = adapter.handle(new IncomingEvent(
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
        assertThat(ignored).extracting(intake -> intake.classification()).containsExactly(
                SlackIntakeClassification.BLANK, SlackIntakeClassification.UNSUPPORTED_CONTENT,
                SlackIntakeClassification.HIDDEN, SlackIntakeClassification.EDIT_OR_DELETE,
                SlackIntakeClassification.UNSUPPORTED_CONTENT);
    }

    @Test
    void forwards_a_bound_thread_reply_without_an_addressing_mention() {
        AtomicReference<MessageSubmission> accepted = new AtomicReference<>();
        MessageHandler adapter = new MessageHandler("UBOT", intake -> {
            accepted.set(intake);
            return SlackIntakeResult.resolvedSessionAccepted(UUID.randomUUID(), UUID.randomUUID());
        });

        SlackEventOutcome outcome = adapter.handle(new IncomingEvent(
                "E-thread", "T1", "C1", "1.000002", "1.000001", "U2", "", "channel", "a reply", "", false, false));

        assertThat(outcome).isEqualTo(SlackEventOutcome.ACCEPTED);
        assertThat(accepted.get().rootThreadTs()).isEqualTo("1.000001");
        assertThat(accepted.get().message().orElseThrow().message()).isEqualTo("a reply");
    }

    @Test
    void does_not_return_an_acknowledgement_when_durable_intake_fails() {
        MessageHandler adapter = new MessageHandler("UBOT", intake -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThatThrownBy(() -> adapter.handle(new IncomingEvent(
                "E1", "T1", "C1", "1.000001", "", "U1", "", "channel", "<@UBOT> hello", "", false, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void logs_committed_new_and_duplicate_intake_with_the_same_session_and_job_correlations() {
        UUID sessionId = UUID.randomUUID();
        UUID messageJobId = UUID.randomUUID();
        AtomicInteger deliveries = new AtomicInteger();
        MessageHandler adapter = new MessageHandler("UBOT", intake -> deliveries.getAndIncrement() == 0
                ? SlackIntakeResult.newSessionAccepted(sessionId, messageJobId)
                : SlackIntakeResult.duplicateAccepted(sessionId, messageJobId));
        Logger logger = (Logger) LoggerFactory.getLogger(MessageHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        IncomingEvent event = new IncomingEvent(
                "E1", "T1", "C1", "1.000001", "", "U1", "", "channel",
                "<@UBOT> hello", "", false, false);
        try {
            assertThat(adapter.handle(event)).isEqualTo(SlackEventOutcome.ACCEPTED);
            assertThat(adapter.handle(event)).isEqualTo(SlackEventOutcome.ACCEPTED);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<Map<String, Object>> inboundLogs = appender.list.stream()
                .map(MessageHandlerTest::keyValues)
                .filter(values -> "slack_inbound".equals(values.get("event")))
                .toList();
        List<Map<String, Object>> sessionLogs = appender.list.stream()
                .map(MessageHandlerTest::keyValues)
                .filter(values -> String.valueOf(values.get("event")).startsWith("session_"))
                .toList();
        assertThat(inboundLogs).hasSize(2);
        assertThat(sessionLogs).hasSize(2);
        Map<String, Object> first = inboundLogs.get(0);
        Map<String, Object> duplicate = inboundLogs.get(1);
        assertThat(first).containsEntry("slackEventId", "E1")
                .containsEntry("disposition", "NEW_ACCEPTED")
                .containsEntry("sessionResolution", "CREATED")
                .containsEntry("sessionId", sessionId.toString())
                .containsEntry("messageJobId", messageJobId.toString());
        assertThat(duplicate).containsEntry("slackEventId", "E1")
                .containsEntry("disposition", "DUPLICATE_ACCEPTED")
                .containsEntry("sessionResolution", "RESOLVED")
                .containsEntry("sessionId", sessionId.toString())
                .containsEntry("messageJobId", messageJobId.toString());
        assertThat(sessionLogs).extracting(values -> values.get("event"))
                .containsExactly("session_created", "session_resolved");
        assertThat(sessionLogs).allSatisfy(values -> assertThat(values)
                .containsEntry("sessionId", sessionId.toString())
                .containsEntry("messageJobId", messageJobId.toString()));
    }

    private static SlackIntakeResult resultFor(MessageSubmission intake) {
        return intake.classification() == SlackIntakeClassification.ACCEPTED
                ? SlackIntakeResult.newSessionAccepted(UUID.randomUUID(), UUID.randomUUID())
                : SlackIntakeResult.newIgnored();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
