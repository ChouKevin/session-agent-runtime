package com.java.system.sessionagent.slack;

import com.java.system.sessionagent.conversation.domain.IncomingMessage;
import com.java.system.sessionagent.conversation.domain.IncomingMessageSource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public final class SlackEventAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlackEventAdapter.class);
    private final String botUserId;
    private final SlackRootIntakePort rootIntakePort;

    public SlackEventAdapter(String botUserId, SlackRootIntakePort rootIntakePort) {
        Assert.notNull(botUserId, "Slack bot user ID must not be null");
        Assert.notNull(rootIntakePort, "Slack root intake port must not be null");
        this.botUserId = botUserId;
        this.rootIntakePort = rootIntakePort;
    }

    public SlackEventOutcome handle(SlackRootEvent event) {
        Assert.notNull(event, "Slack root event must not be null");
        if (isObviousNonHumanNoise(event)) {
            logInbound(event, event.messageTs(), SlackEventOutcome.IGNORED);
            return SlackEventOutcome.IGNORED;
        }
        String rootThreadTs = StringUtils.hasText(event.threadTs()) ? event.threadTs() : event.messageTs();
        SlackIntakeClassification ignoredClassification = ignoredClassification(event);
        if (ignoredClassification != SlackIntakeClassification.ACCEPTED) {
            SlackEventOutcome outcome = rootIntakePort.receive(new SlackRootIntake(event.eventId(), event.teamId(), event.channelId(), rootThreadTs,
                    event.messageTs(), ignoredClassification, Optional.empty()));
            logInbound(event, rootThreadTs, outcome);
            return outcome;
        }
        String message = normalizedMessage(event);
        if (!StringUtils.hasText(message)) {
            SlackEventOutcome outcome = rootIntakePort.receive(new SlackRootIntake(event.eventId(), event.teamId(), event.channelId(), rootThreadTs,
                    event.messageTs(), SlackIntakeClassification.BLANK, Optional.empty()));
            logInbound(event, rootThreadTs, outcome);
            return outcome;
        }
        IncomingMessage incomingMessage = new IncomingMessage(
                IncomingMessageSource.SLACK,
                boundedKey("slack/", event.teamId(), event.channelId(), rootThreadTs),
                event.participantId(),
                boundedKey("slack/", event.teamId(), event.channelId(), event.messageTs()),
                message);
        SlackEventOutcome outcome = rootIntakePort.receive(new SlackRootIntake(event.eventId(), event.teamId(), event.channelId(), rootThreadTs,
                event.messageTs(), SlackIntakeClassification.ACCEPTED, Optional.of(incomingMessage)));
        logInbound(event, rootThreadTs, outcome);
        return outcome;
    }

    private static void logInbound(SlackRootEvent event, String rootThreadTs, SlackEventOutcome outcome) {
        LOGGER.atInfo().addKeyValue("event", "slack_inbound")
                .addKeyValue("slackTeamId", event.teamId())
                .addKeyValue("slackChannelId", event.channelId())
                .addKeyValue("slackThreadTs", rootThreadTs)
                .addKeyValue("slackMessageTs", event.messageTs())
                .addKeyValue("outcome", outcome.name()).log("runtime_lifecycle");
    }

    private boolean isObviousNonHumanNoise(SlackRootEvent event) {
        if (!StringUtils.hasText(event.participantId()) || StringUtils.hasText(event.botId())
                || botUserId.equals(event.participantId())) {
            return true;
        }
        return "bot_message".equals(event.subtype()) || "channel_join".equals(event.subtype())
                || "channel_leave".equals(event.subtype());
    }

    private SlackIntakeClassification ignoredClassification(SlackRootEvent event) {
        if (event.hidden()) {
            return SlackIntakeClassification.HIDDEN;
        }
        if ("message_changed".equals(event.subtype()) || "message_deleted".equals(event.subtype())) {
            return SlackIntakeClassification.EDIT_OR_DELETE;
        }
        if (StringUtils.hasText(event.subtype())) {
            return SlackIntakeClassification.UNSUPPORTED_CONTENT;
        }
        if (event.hasAttachments()) {
            return SlackIntakeClassification.UNSUPPORTED_CONTENT;
        }
        return SlackIntakeClassification.ACCEPTED;
    }

    private String normalizedMessage(SlackRootEvent event) {
        if (!StringUtils.hasText(event.text())) {
            return "";
        }
        if (StringUtils.hasText(event.threadTs()) && !event.messageTs().equals(event.threadTs())) {
            return event.text().trim();
        }
        if ("im".equals(event.channelType())) {
            return event.text().trim();
        }
        String addressingMention = "<@" + botUserId + ">";
        int addressingIndex = event.text().indexOf(addressingMention);
        if (addressingIndex < 0) {
            return "";
        }
        return (event.text().substring(0, addressingIndex)
                + event.text().substring(addressingIndex + addressingMention.length())).trim();
    }

    private static String boundedKey(String prefix, String... parts) {
        String value = prefix + String.join("/", parts);
        Assert.isTrue(value.length() <= 256, "Slack source identifiers must not exceed 256 characters");
        return value;
    }
}
